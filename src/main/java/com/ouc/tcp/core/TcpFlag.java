package com.ouc.tcp.core;

/**
 * TCP control flags represented by the teaching framework.
 */
public enum TcpFlag {
    FIN(0x01),
    SYN(0x02),
    RST(0x04),
    PSH(0x08),
    ACK(0x10),
    URG(0x20);

    private final int mask;

    TcpFlag(int mask) {
        this.mask = mask;
    }

    public int mask() {
        return mask;
    }
}
