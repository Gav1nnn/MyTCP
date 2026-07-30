package com.ouc.tcp.trace;

import com.ouc.tcp.core.TcpSegment;
import com.ouc.tcp.transport.SegmentTransport;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

/**
 * Records every transport attempt without changing segment semantics.
 */
public final class TracingSegmentTransport implements SegmentTransport {
    private final SegmentTransport delegate;
    private final ProtocolTrace trace;

    public TracingSegmentTransport(
            SegmentTransport delegate,
            ProtocolTrace trace) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.trace = Objects.requireNonNull(trace, "trace");
    }

    @Override
    public void send(TcpSegment segment) throws IOException {
        trace.segment(SegmentDirection.SEND, segment);
        delegate.send(segment);
    }

    @Override
    public TcpSegment receive(Duration timeout) throws IOException {
        TcpSegment segment = delegate.receive(timeout);
        trace.segment(SegmentDirection.RECEIVE, segment);
        return segment;
    }

    @Override
    public void close() {
        delegate.close();
    }
}
