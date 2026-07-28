package com.ouc.tcp.core;

import java.util.List;
import java.util.Objects;

/**
 * Immutable result of processing an acknowledgment at the sender.
 */
public final class AckProcessingResult {
    private final AckDisposition disposition;
    private final long newlyAcknowledgedBytes;
    private final boolean windowChanged;
    private final List<TcpSegment> transmissions;

    public AckProcessingResult(
            AckDisposition disposition,
            long newlyAcknowledgedBytes,
            boolean windowChanged,
            List<TcpSegment> transmissions) {
        this.disposition = Objects.requireNonNull(disposition, "disposition");
        if (newlyAcknowledgedBytes < 0) {
            throw new IllegalArgumentException(
                    "newlyAcknowledgedBytes must not be negative");
        }
        this.newlyAcknowledgedBytes = newlyAcknowledgedBytes;
        this.windowChanged = windowChanged;
        this.transmissions = List.copyOf(
                Objects.requireNonNull(transmissions, "transmissions"));
    }

    public AckDisposition disposition() {
        return disposition;
    }

    public long newlyAcknowledgedBytes() {
        return newlyAcknowledgedBytes;
    }

    public boolean windowChanged() {
        return windowChanged;
    }

    public List<TcpSegment> transmissions() {
        return transmissions;
    }
}
