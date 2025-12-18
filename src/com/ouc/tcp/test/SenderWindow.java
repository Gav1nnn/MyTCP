package com.ouc.tcp.test;

import com.ouc.tcp.client.Client;
import com.ouc.tcp.client.UDT_RetransTask;
import com.ouc.tcp.message.TCP_PACKET;

public class SenderWindow {
    private int base;
    private int nextexcepted;
    private int rear;
    private int size;
    private SenderStru[] window;

    public SenderWindow(int size){
        this.base = 0;
        this.nextexcepted = 0;
        this.rear = 0;
        this.size = size;
        this.window = new SenderStru[size];
        for(int i=0; i<size; i++){
            this.window[i] = new SenderStru();
        }
        
    }

    public boolean isFull() {return rear-base == size;}

    public boolean isEmpty() {return rear == base;}

    public boolean isFinished() {return nextexcepted == rear;}

    private int getIdx(int seq) {return seq % size;}

    // 将包放入窗口
    public void pushPacket(TCP_PACKET packet) {
        int idx = getIdx(rear);
        // 初始化
        window[idx].setWindow(packet, SenderFlag.NOT_ACKED.ordinal());
        rear++;
    }

    // 发送窗口中未发送的包，并启动计时器
    public void sendPacket(TCP_Sender sender, Client client, int delay, int period){
        // 窗口为空或者已发送
        if(isEmpty() || isFinished()) return ;

        int idx = getIdx(nextexcepted);
        TCP_PACKET packet = window[idx].getPacket();

        // 为这个包启动计时器
        window[idx].timePacket(new UDT_RetransTask(client, packet), delay, period);
        nextexcepted++;

        // 发送
        sender.udt_send(packet);
    }

    // 处理ACK以及窗口滑动
    public void ackPacket(int seq){
        // 遍历窗口寻找对应的序列号
        for (int i=base; i != rear; i++){
            int idx = getIdx(i);
            if (window[idx].getPacket().getTcpH().getTh_seq() == seq && !window[idx].isAcked()){
                window[idx].ackPacket();
                break;
            }
        }

        // 滑动窗口，如果base位置的包已经确认，向右移动
        while (base != rear && window[getIdx(base)].isAcked()) {
            int idx = getIdx(base);
            window[idx].resetWindow();
            base++;
        }
    }
}
