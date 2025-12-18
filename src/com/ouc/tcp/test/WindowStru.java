package com.ouc.tcp.test;

import com.ouc.tcp.message.TCP_PACKET;

public class WindowStru {

    protected TCP_PACKET packet;
    protected int flag;

    public WindowStru(){
        packet = null;
        flag = 0;
    }

    public void setWindow(TCP_PACKET packet, int flag){
        this.packet = packet;
        this.flag = flag; 
    }

    public void resetWindow(){
        packet = null;
        flag = 0;
    }

    public TCP_PACKET getPacket(){
        return packet;
    }

}