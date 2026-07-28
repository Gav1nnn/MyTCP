package com.ouc.tcp.core;

import java.util.Objects;

/**
 * Immutable outcome of processing one incoming segment.
 */
public final class ReceiveResult {
    private final ReceiveDisposition disposition;
    private final SequenceNumber32 acknowledgmentNumber;
    private final int advertisedWindow;
    private final byte[] deliveredBytes;
    private final boolean acknowledgmentRequired;

    public ReceiveResult(
            ReceiveDisposition disposition,
            SequenceNumber32 acknowledgmentNumber,
            int advertisedWindow,
            byte[] deliveredBytes,
            boolean acknowledgmentRequired) {
        this.disposition = Objects.requireNonNull(disposition, "disposition");
        this.acknowledgmentNumber =
                Objects.requireNonNull(acknowledgmentNumber, "acknowledgmentNumber");
        if (advertisedWindow < 0 || advertisedWindow > TcpSegment.MAX_WINDOW) {
            throw new IllegalArgumentException(
                    "advertisedWindow must be an unsigned 16-bit value");
        }
        this.advertisedWindow = advertisedWindow;
        this.deliveredBytes = Objects.requireNonNull(deliveredBytes, "deliveredBytes").clone();
        this.acknowledgmentRequired = acknowledgmentRequired;
    }

    public ReceiveDisposition disposition() {
        return disposition;
    }

    public SequenceNumber32 acknowledgmentNumber() {
        return acknowledgmentNumber;
    }

    public int advertisedWindow() {
        return advertisedWindow;
    }

    public byte[] deliveredBytes() {
        return deliveredBytes.clone();
    }

    public boolean acknowledgmentRequired() {
        return acknowledgmentRequired;
    }
}
