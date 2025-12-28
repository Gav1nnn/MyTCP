package com.ouc.tcp.test;

import com.ouc.tcp.client.Client;
import com.ouc.tcp.client.UDT_RetransTask;
import com.ouc.tcp.client.UDT_Timer;
import com.ouc.tcp.message.TCP_PACKET;

import java.util.TimerTask;
import java.util.concurrent.LinkedBlockingDeque;

public class SenderWindow {
    private final int size = 16;
    private UDT_Timer timer;
    private final TCP_Sender sender;

    private final LinkedBlockingDeque<SenderStru> window;

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
            window.sendWindow();
        }
    }

    public boolean isEmpty() {return window.isEmpty();}

    public boolean isFull() {return window.size()>=size;}

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
