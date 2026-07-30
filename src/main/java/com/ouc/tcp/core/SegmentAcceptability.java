package com.ouc.tcp.core;

public enum SegmentAcceptability {
    ACCEPTABLE,
    OUTSIDE_WINDOW,
    WRONG_CONNECTION,
    CHECKSUM_FAILED
}
