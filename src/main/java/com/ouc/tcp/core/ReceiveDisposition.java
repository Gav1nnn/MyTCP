package com.ouc.tcp.core;

public enum ReceiveDisposition {
    CHECKSUM_FAILED,
    NO_DATA,
    IN_ORDER,
    OUT_OF_ORDER,
    DUPLICATE,
    OUTSIDE_WINDOW
}
