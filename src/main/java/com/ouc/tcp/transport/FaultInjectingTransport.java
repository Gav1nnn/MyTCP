package com.ouc.tcp.transport;

import com.ouc.tcp.core.TcpSegment;
import com.ouc.tcp.trace.ProtocolTrace;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

/**
 * Applies a deterministic outbound fault plan to a segment transport.
 */
public final class FaultInjectingTransport implements SegmentTransport {
    private final SegmentTransport delegate;
    private final FaultPlan plan;
    private final ProtocolTrace trace;

    private long transmissionNumber;
    private TcpSegment heldForReordering;

    public FaultInjectingTransport(
            SegmentTransport delegate,
            FaultPlan plan) {
        this(delegate, plan, ProtocolTrace.none());
    }

    public FaultInjectingTransport(
            SegmentTransport delegate,
            FaultPlan plan,
            ProtocolTrace trace) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.trace = Objects.requireNonNull(trace, "trace");
    }

    @Override
    public synchronized void send(TcpSegment segment) throws IOException {
        Objects.requireNonNull(segment, "segment");
        long currentTransmission = ++transmissionNumber;
        FaultAction action = plan.actionFor(currentTransmission);
        if (action != FaultAction.PASS) {
            trace.fault(currentTransmission, action, segment);
        }

        if (action == FaultAction.REORDER) {
            if (heldForReordering != null) {
                throw new IllegalStateException(
                        "consecutive reorder actions are not supported");
            }
            heldForReordering = segment;
            return;
        }

        switch (action) {
            case PASS -> delegate.send(segment);
            case DROP -> {
            }
            case CORRUPT -> delegate.send(
                    segment.withChecksum(segment.checksum() ^ 1));
            case DUPLICATE -> {
                delegate.send(segment);
                delegate.send(segment);
            }
            case REORDER -> throw new AssertionError(
                    "reorder action was handled above");
        }

        if (heldForReordering != null) {
            delegate.send(heldForReordering);
            heldForReordering = null;
        }
    }

    @Override
    public TcpSegment receive(Duration timeout) throws IOException {
        return delegate.receive(timeout);
    }

    @Override
    public void close() {
        delegate.close();
    }

    public synchronized long transmissionCount() {
        return transmissionNumber;
    }
}
