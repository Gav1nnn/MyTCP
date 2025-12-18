package com.ouc.tcp.test;

import com.ouc.tcp.message.TCP_PACKET;

public class ReceiverWindow {
    private int base;
    private final int size;
    private final ReceiverStru[] window;

    private int getIdx(int seq){
        return seq % size;
    }
    
    public ReceiverWindow(int size){
        this.base = 0;
        this.size = size;
        this.window = new ReceiverStru[size];
        for (int i=0; i<size; i++){
            this.window[i] = new ReceiverStru();
        }
    }

    public int bufferPacket(TCP_PACKET packet){
        int seq = (packet.getTcpH().getTh_seq()-1) / packet.getTcpS().getData().length;
        // 提前送达但在窗口范围内
        if (seq >= base + size){
            return AckFlag.UNORDERED.ordinal();
        } 
        // 已经滑动过，重复包
        if (seq < base){
            return AckFlag.DUPLICATE.ordinal();
        }
        // 窗口内的包缓存
        window[getIdx(seq)].setWindow(packet, ReceiverFlag.BUFFERED.ordinal());

        // 期望的包，交付
        if (seq == base){
            return AckFlag.IS_BASE.ordinal();
        }

        return AckFlag.ORDERED.ordinal();
    }

    public TCP_PACKET getPacket(){
        // 窗口左侧无法进行交付
        if (!window[getIdx(base)].isBuffered()) return null;

        // 交付数据
        TCP_PACKET packet = window[getIdx(base)].getPacket();
        window[getIdx(base)].resetWindow();
        base++;
        return packet;
    }
}
