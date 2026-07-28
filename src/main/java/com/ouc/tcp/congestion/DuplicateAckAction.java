package com.ouc.tcp.congestion;

/**
 * Sender action requested after processing a duplicate acknowledgment.
 */
public enum DuplicateAckAction {
    NONE,
    LIMITED_TRANSMIT,
    FAST_RETRANSMIT
}
