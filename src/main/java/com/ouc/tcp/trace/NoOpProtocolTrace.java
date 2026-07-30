package com.ouc.tcp.trace;

import com.ouc.tcp.connection.TcpState;
import com.ouc.tcp.core.TcpSegment;
import com.ouc.tcp.transport.FaultAction;

enum NoOpProtocolTrace implements ProtocolTrace {
    INSTANCE;

    @Override
    public void segment(
            SegmentDirection direction,
            TcpSegment segment) {
    }

    @Override
    public void stateTransition(
            TcpState previous,
            TcpState current) {
    }

    @Override
    public void senderSnapshot(
            TcpState state,
            SenderSnapshot snapshot) {
    }

    @Override
    public void fault(
            long transmissionNumber,
            FaultAction action,
            TcpSegment segment) {
    }
}
