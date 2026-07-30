package com.ouc.tcp.trace;

import com.ouc.tcp.connection.TcpState;
import com.ouc.tcp.core.TcpSegment;

/**
 * Structured observation boundary for protocol behavior.
 */
public interface ProtocolTrace extends AutoCloseable {
    void segment(SegmentDirection direction, TcpSegment segment);

    void stateTransition(TcpState previous, TcpState current);

    void senderSnapshot(TcpState state, SenderSnapshot snapshot);

    @Override
    default void close() {
    }

    static ProtocolTrace none() {
        return NoOpProtocolTrace.INSTANCE;
    }
}
