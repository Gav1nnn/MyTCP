package com.ouc.tcp.test;

enum SenderFlag{
    NOT_ACKED,
    ACKED
}

enum WindowFlag{
    FULL,
    NOT_FULL
}

enum ReceiverFlag{
    WAIT,
    BUFFERED
}

enum AckFlag{
    ORDERED,
    DUPLICATE, 
    UNORDERED, 
    IS_BASE
}