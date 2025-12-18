package com.ouc.tcp.test;

import com.ouc.tcp.client.UDT_RetransTask;
import com.ouc.tcp.client.UDT_Timer;

public class SenderStru extends WindowStru{
    private UDT_Timer timer;

    public SenderStru(){
        super();
        this.timer = null;
    }

    public boolean isAcked(){
        return flag == SenderFlag.ACKED.ordinal();
    }
    // SR核心：递归为每个包独立启用计时器
    public void timePacket(UDT_RetransTask retransTask, int delay, int period){
        this.timer = new UDT_Timer();
        this.timer.schedule(retransTask, delay, period);
    }

    // 收到ACK后，进行标记并取消计时器
    public void ackPacket(){
        this.flag = SenderFlag.ACKED.ordinal();
        if (this.timer != null){
            this.timer.cancel();
        }
    }

}
