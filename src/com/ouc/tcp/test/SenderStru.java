package com.ouc.tcp.test;

import com.ouc.tcp.client.UDT_RetransTask;
import com.ouc.tcp.client.UDT_Timer;
import com.ouc.tcp.message.TCP_PACKET;

public class SenderStru extends WindowStru{

    public SenderStru(){
        super();
    }

    public SenderStru(TCP_PACKET packet, int flag){
        super();
        this.packet = packet;
        this.flag = flag;
    }

    public boolean isAcked(){
        return flag == SenderFlag.ACKED.ordinal();
    }

    // 收到ACK后，进行标记并取消计时器
    public void ackPacket(){
        this.flag = SenderFlag.ACKED.ordinal();
    }

}
