package com.ouc.tcp.test;

import com.ouc.tcp.client.UDT_Timer;
import com.ouc.tcp.message.TCP_PACKET;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.TimerTask;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * 可视化版 SenderWindow：只输出三列 CSV
 * Time(ms),Cwnd,Ssthresh
 *
 * 重点：
 * 1) 所有写 CSV 的地方都加锁，避免多线程粘行导致 pandas ParserError
 * 2) 只记录三列，不写事件文本，不写逗号字段
 */
public class SenderWindowViz {

    private final int size = 16;                 // 最大窗口大小（接收方窗口/GBN窗口上限）
    private final TCP_Sender sender;             // 发送端对象
    private final LinkedBlockingDeque<SenderStru> window;

    // 拥塞控制变量
    private int cwnd = 1;
    private double cwnd_d = 1.0;
    private int ssthresh = 16;

    // dupACK 计数（快重传）
    private int lastAck = -1;
    private int dupAckCount = 0;
    private final int DUP_ACK_THRESHOLD = 3;

    // 计时器（GBN 超时重传）
    private UDT_Timer timer;
    private final int delay = 3000;
    private final int period = 3000;

    // CSV
    private static final String CSV_FILE_PATH = "tcp_data.csv";
    private final BufferedWriter csvWriter;
    private final Object csvLock = new Object();
    private final long startTime;

    public SenderWindowViz(TCP_Sender sender) {
        this.sender = sender;
        this.window = new LinkedBlockingDeque<>();
        this.timer = new UDT_Timer();
        this.startTime = System.currentTimeMillis();

        try {
            // 覆盖写（false），每次运行生成新的 tcp_data.csv
            this.csvWriter = new BufferedWriter(new FileWriter(CSV_FILE_PATH, false));
            synchronized (csvLock) {
                csvWriter.write("Time(ms),Cwnd,Ssthresh\n");
                csvWriter.flush();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to create CSV file: " + e.getMessage(), e);
        }

        // 记录初始值
        record();
    }

    // ========== TimerTask ==========
    public class GBNTimeroutTask extends TimerTask {
        private final SenderWindowViz w;

        public GBNTimeroutTask(SenderWindowViz w) {
            this.w = w;
        }

        @Override
        public void run() {
            synchronized (w) {
                w.onTimeout();
                w.resendWindow();
                w.record(); // 超时后也记录一次
            }
        }
    }

    // ========== 基本状态 ==========
    private int effectiveCwnd() {
        return Math.min(cwnd, size);
    }

    public boolean isEmpty() {
        return window.isEmpty();
    }

    public boolean isFull() {
        return window.size() >= effectiveCwnd();
    }

    // ========== 核心：记录三列 ==========
    private void record() {
        long t = System.currentTimeMillis() - startTime;
        synchronized (csvLock) {
            try {
                csvWriter.write(t + "," + cwnd + "," + ssthresh + "\n");
                csvWriter.flush();
            } catch (IOException e) {
                System.err.println("Failed to write CSV: " + e.getMessage());
            }
        }
    }

    // ========== 计时器管理 ==========
    private void startTimerIfNeeded(boolean wasEmpty) {
        if (wasEmpty && !window.isEmpty()) {
            timer = new UDT_Timer();
            timer.schedule(new GBNTimeroutTask(this), delay, period);
        }
    }

    private void resetTimer() {
        timer.cancel();
        timer = new UDT_Timer();
        if (!window.isEmpty()) {
            timer.schedule(new GBNTimeroutTask(this), delay, period);
        }
    }

    private void stopTimerIfEmpty() {
        if (window.isEmpty()) {
            timer.cancel();
        }
    }

    // ========== 发送 / 重传 ==========
    // 超时后重发窗口内未确认的包
    private void resendWindow() {
        for (SenderStru stru : window) {
            if (!stru.isAcked()) {
                sender.udt_send(stru.getPacket());
            }
        }
    }

    // 快重传：重发 ack 后面的那个包（按你原来逻辑：expectedSeq = ack + seglen）
    private void resendPacket(int ack) {
        SenderStru first = window.peekFirst();
        if (first == null) return;

        int seglen = first.getPacket().getTcpS().getData().length;
        int expectedSeq = ack + seglen;

        for (SenderStru stru : window) {
            int seq = stru.getPacket().getTcpH().getTh_seq();
            if (seq == expectedSeq) {
                sender.udt_send(stru.getPacket());
                break;
            }
        }
    }

    // ========== Tahoe/Reno：拥塞窗口更新 ==========
    // 收到“新的累计确认”时更新 cwnd（慢启动/拥塞避免）
    private void onNewAck() {
        if (cwnd < ssthresh) {
            cwnd++;
            cwnd_d = cwnd;
        } else {
            cwnd_d += 1.0 / cwnd;
            cwnd = (int) cwnd_d;
        }
    }

    // 超时：ssthresh = cwnd/2, cwnd=1
    private void onTimeout() {
        ssthresh = Math.max(1, cwnd / 2);
        cwnd = 1;
        cwnd_d = 1.0;
        lastAck = -1;
        dupAckCount = 0;
    }

    // 3 dupACK：ssthresh = cwnd/2, cwnd=1, 快重传一个包（Tahoe风格）
    private void onDupAckThreshold(int ack) {
        ssthresh = Math.max(1, cwnd / 2);
        cwnd = ssthresh;
        cwnd_d = (double) cwnd;
        resendPacket(ack);
        dupAckCount = 0;
    }

    // ========== 对外接口：入窗并发送 ==========
    public void putAndSend(TCP_PACKET packet) {
        synchronized (this) {
            while (isFull()) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            boolean wasEmpty = window.isEmpty();
            SenderStru stru = new SenderStru(packet, SenderFlag.NOT_ACKED.ordinal());
            window.addLast(stru);

            startTimerIfNeeded(wasEmpty);

            // 立即发送
            sender.udt_send(stru.getPacket());

            // 记录一次（发包后）
            record();
        }
    }

    // ========== 对外接口：处理 ACK / 滑动窗口 ==========
    public void ackPacket(int ack) {
        synchronized (this) {
            boolean removed = false;

            // GBN累计确认：弹出队头 seq <= ack
            while (true) {
                SenderStru first = window.peekFirst();
                if (first == null) break;

                int seq = first.getPacket().getTcpH().getTh_seq();
                if (seq <= ack) {
                    window.pollFirst();
                    first.ackPacket();
                    removed = true;
                } else {
                    break;
                }
            }

            // 只有窗口真的前移（收到新确认）才更新 cwnd
            if (removed) {
                onNewAck();
            }

            // dupACK 统计
            if (ack == lastAck) {
                dupAckCount++;
            } else {
                lastAck = ack;
                dupAckCount = 1;
            }

            // 3dupACK 快重传
            if (dupAckCount >= DUP_ACK_THRESHOLD) {
                onDupAckThreshold(ack);
            }

            // 计时器：base 前移则重置；空则停
            if (removed) {
                if (window.isEmpty()) {
                    stopTimerIfEmpty();
                } else {
                    resetTimer();
                }
                this.notifyAll();
            }

            // 每次 ack 处理完记录一次（保证曲线完整）
            record();
        }
    }

    // ========== 资源释放 ==========
    public void close() {
        synchronized (csvLock) {
            try {
                csvWriter.close();
            } catch (IOException e) {
                System.err.println("Failed to close CSV: " + e.getMessage());
            }
        }
        timer.cancel();
    }
}
