package com.ouc.tcp.test;

import com.ouc.tcp.client.Client;
import com.ouc.tcp.client.UDT_RetransTask;
import com.ouc.tcp.client.UDT_Timer;
import com.ouc.tcp.message.TCP_PACKET;

import java.util.TimerTask;
import java.util.concurrent.LinkedBlockingDeque;

public class SenderWindow {
    private final int size = 16; //窗口大小
    private UDT_Timer timer; // 计时器
    private final TCP_Sender sender; // 发送端对象

    // 新增dupACK计数
    private int lastAck = -1; // 上一个ACK序号
    private int dupAckCount = 0; // 重复ACK计数
    private final int DUP_ACK_THRESHOLD = 3; // 触发快速重传的重复ACK阈值

    private final LinkedBlockingDeque<SenderStru> window; // 发送窗口

    private int cwnd = 1; //拥塞窗口初始值为1
    private double cwnd_d = 1.0; //拥塞窗口的双精度值，用于慢启动阶段的指数增长
    private int ssthresh = 16; //慢启动阈值初始值为16


    public SenderWindow(TCP_Sender sender){
        this.sender = sender;
        this.window = new LinkedBlockingDeque<>();
        this.timer = new UDT_Timer(); 
    }

    public class GBNTimeroutTask extends TimerTask {
        private final SenderWindow window;

        public GBNTimeroutTask(SenderWindow window){
            this.window = window;
        }

        // 超时重传整个窗口
        @Override
        public void run(){
            synchronized(window) {
                window.onTimeout();
                window.sendWindow();
            }
            window.sendWindow();
        }
    }

    private int effectiveCwnd() {
        return Math.min(cwnd, size);
    }

    public boolean isEmpty() {return window.isEmpty();}

    public boolean isFull() {return window.size()>=effectiveCwnd();}

    // Tahoe:快重传
    private void resendPacket(int ack){
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

    // Tahoe:超时重传
    private void onTimeout(){
        ssthresh = Math.max(1, cwnd / 2);
        cwnd = 1;
        cwnd_d = 1.0;
        lastAck = -1;
        dupAckCount = 0;
    }

    // Tahoe:处理收到的ACK
    public void onNewAck(int ack){
        if(cwnd < ssthresh){
            cwnd++;
            cwnd_d = cwnd;
        } else {
            cwnd_d += 1.0 / cwnd;
            cwnd = (int)cwnd_d;
        }
    }

    // 先移除再重置timer
    public void resetTimer(){
        timer.cancel();
        timer = new UDT_Timer();
        if (!isEmpty()){
            timer.schedule(new GBNTimeroutTask(this), 3000, 3000);
        }
    }

    // 阻塞式入窗并发送
    public void putAndSend(TCP_PACKET packet) {
        synchronized(this) {
            // 1.窗口满就阻塞等待
            while (isFull()) {
                try{
                    this.wait();
                } catch(InterruptedException e){
                    Thread.currentThread().interrupt();
                    return;
                }
            }
    

        // 2.若窗口之前为空：从空->非空，需要启动计时器
        boolean wasEmpty = window.isEmpty();

        // 3.入窗（放队尾：保持发送顺序）
        SenderStru stru = new SenderStru(packet, SenderFlag.NOT_ACKED.ordinal());
        window.addLast(stru);

        // 4.启动计时器（只在空->非空时）
        if (wasEmpty) {
            if (!window.isEmpty()) {
            timer = new UDT_Timer();
            timer.schedule(new GBNTimeroutTask(this), 3000, 3000);
            }
        }

        // 5.立即发送这个新加入的包
        sender.udt_send(stru.getPacket());
        }
    }



    // 超时后，把窗口里还没被确认的包，全部重新发送
    public void sendWindow(){
        for(SenderStru pack : window){
            if (pack.isAcked() == false) {
                sender.udt_send(pack.getPacket());
            }
        }
    }

    // 处理ACK以及窗口滑动
    public void ackPacket(int ack){
        synchronized(this){
            boolean remove = false;

            // GBN 累计确认：队头 seq <= ack 的都可以移除
            while (true) {
                SenderStru first = window.peekFirst();
                if (first == null) break;

                int seq = first.getPacket().getTcpH().getTh_seq();
                if (seq <= ack) {
                    window.pollFirst();
                    first.ackPacket();
                    remove = true;
                } else {
                    break;
                }
            }
            // Tahoe:cwnd调整
            if (remove) {
                onNewAck(ack);
            }

            // Tahoe:dupACK处理 + 3dupACK快重传
            if (ack == lastAck) {
                dupAckCount++;
            } else {
                lastAck = ack;
                dupAckCount = 1;
            }
            
            if (dupAckCount >= DUP_ACK_THRESHOLD) {
                ssthresh = Math.max(1, cwnd / 2);
                cwnd = 1;
                cwnd_d = (double)cwnd;
                resendPacket(ack);
                dupAckCount = 0; // 重置重复ACK计数
            }



            // 计时器&唤醒发送线程  
            if (remove) {
                // base前移了：重置计时器
                if (window.isEmpty()){
                    timer.cancel();
                } else {
                    resetTimer();
                }

                // 窗口有空位了：唤醒阻塞等待的发送线程
                this.notifyAll();
            }
        }
    }
}
