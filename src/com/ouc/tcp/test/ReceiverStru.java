package com.ouc.tcp.test;

public class ReceiverStru extends WindowStru{
    public ReceiverStru(){
        super();
    }

    public boolean isBuffered(){
        return flag == ReceiverFlag.BUFFERED.ordinal();
    }
}
