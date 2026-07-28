package com.ouc.tcp.congestion;

/**
 * Observable phases of RFC 5681 Reno congestion control.
 */
public enum CongestionPhase {
    SLOW_START,
    CONGESTION_AVOIDANCE,
    FAST_RECOVERY
}
