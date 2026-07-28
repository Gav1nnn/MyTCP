package com.ouc.tcp.core;

public enum AckDisposition {
    WRONG_CONNECTION,
    CHECKSUM_FAILED,
    NOT_AN_ACK,
    FUTURE_ACK,
    UNACCEPTABLE_ACK,
    OLD_ACK,
    DUPLICATE_ACK,
    NEW_ACK
}
