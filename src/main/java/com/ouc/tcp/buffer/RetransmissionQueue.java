package com.ouc.tcp.buffer;

import com.ouc.tcp.checksum.TcpChecksum;
import com.ouc.tcp.core.SequenceNumber32;
import com.ouc.tcp.core.TcpSegment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Ordered data segments retained until cumulatively acknowledged.
 */
public final class RetransmissionQueue {
    private final Deque<TcpSegment> segments = new ArrayDeque<>();
    private long bytesInFlight;

    public void add(TcpSegment segment) {
        Objects.requireNonNull(segment, "segment");
        if (segment.payloadLength() == 0) {
            throw new IllegalArgumentException("retransmission queue accepts data segments only");
        }
        if (!segments.isEmpty()) {
            TcpSegment last = segments.getLast();
            SequenceNumber32 expected = SequenceNumber32
                    .of(last.sequenceNumber())
                    .add(last.payloadLength());
            if (expected.toLong() != segment.sequenceNumber()) {
                throw new IllegalArgumentException("segments must be added in sequence order");
            }
        }

        segments.addLast(segment);
        bytesInFlight = Math.addExact(bytesInFlight, segment.payloadLength());
    }

    public AcknowledgmentResult acknowledge(SequenceNumber32 acknowledgmentNumber) {
        Objects.requireNonNull(acknowledgmentNumber, "acknowledgmentNumber");
        long acknowledgedBytes = 0;
        int fullyAcknowledgedSegments = 0;

        while (!segments.isEmpty()) {
            TcpSegment first = segments.getFirst();
            SequenceNumber32 start = SequenceNumber32.of(first.sequenceNumber());
            SequenceNumber32 end = start.add(first.payloadLength());

            if (end.isBeforeOrEqual(acknowledgmentNumber)) {
                segments.removeFirst();
                acknowledgedBytes += first.payloadLength();
                fullyAcknowledgedSegments++;
                continue;
            }

            if (start.isBefore(acknowledgmentNumber)
                    && acknowledgmentNumber.isBefore(end)) {
                int acknowledgedPrefix = Math.toIntExact(
                        start.distanceTo(acknowledgmentNumber));
                byte[] remainingPayload = Arrays.copyOfRange(
                        first.payload(), acknowledgedPrefix, first.payloadLength());
                TcpSegment remaining = TcpChecksum.apply(
                        first.withSequenceNumber(acknowledgmentNumber.toLong())
                                .withPayload(remainingPayload));
                segments.removeFirst();
                segments.addFirst(remaining);
                acknowledgedBytes += acknowledgedPrefix;
            }
            break;
        }

        bytesInFlight -= acknowledgedBytes;
        return new AcknowledgmentResult(
                acknowledgedBytes, fullyAcknowledgedSegments);
    }

    public long bytesInFlight() {
        return bytesInFlight;
    }

    public int segmentCount() {
        return segments.size();
    }

    public List<TcpSegment> segments() {
        return List.copyOf(new ArrayList<>(segments));
    }

    public record AcknowledgmentResult(
            long acknowledgedBytes, int fullyAcknowledgedSegments) {
    }
}
