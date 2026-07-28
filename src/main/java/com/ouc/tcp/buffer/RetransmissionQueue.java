package com.ouc.tcp.buffer;

import com.ouc.tcp.checksum.TcpChecksum;
import com.ouc.tcp.core.SequenceNumber32;
import com.ouc.tcp.core.TcpSegment;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Ordered data segments retained until cumulatively acknowledged.
 */
public final class RetransmissionQueue {
    private final Deque<OutstandingSegment> segments = new ArrayDeque<>();
    private long bytesInFlight;

    public void add(TcpSegment segment) {
        add(segment, 0);
    }

    public void add(TcpSegment segment, long sentAtNanos) {
        Objects.requireNonNull(segment, "segment");
        if (segment.payloadLength() == 0) {
            throw new IllegalArgumentException("retransmission queue accepts data segments only");
        }
        if (!segments.isEmpty()) {
            TcpSegment last = segments.getLast().segment;
            SequenceNumber32 expected = SequenceNumber32
                    .of(last.sequenceNumber())
                    .add(last.payloadLength());
            if (expected.toLong() != segment.sequenceNumber()) {
                throw new IllegalArgumentException("segments must be added in sequence order");
            }
        }

        segments.addLast(new OutstandingSegment(segment, sentAtNanos));
        bytesInFlight = Math.addExact(bytesInFlight, segment.payloadLength());
    }

    public AcknowledgmentResult acknowledge(SequenceNumber32 acknowledgmentNumber) {
        return acknowledge(acknowledgmentNumber, 0);
    }

    public AcknowledgmentResult acknowledge(
            SequenceNumber32 acknowledgmentNumber, long acknowledgedAtNanos) {
        Objects.requireNonNull(acknowledgmentNumber, "acknowledgmentNumber");
        long acknowledgedBytes = 0;
        int fullyAcknowledgedSegments = 0;
        Long sampleNanos = null;
        boolean retransmittedDataAcknowledged = false;

        while (!segments.isEmpty()) {
            OutstandingSegment outstanding = segments.getFirst();
            TcpSegment first = outstanding.segment;
            SequenceNumber32 start = SequenceNumber32.of(first.sequenceNumber());
            SequenceNumber32 end = start.add(first.payloadLength());

            if (end.isBeforeOrEqual(acknowledgmentNumber)) {
                segments.removeFirst();
                acknowledgedBytes += first.payloadLength();
                fullyAcknowledgedSegments++;
                retransmittedDataAcknowledged |= outstanding.retransmitted;
                if (!outstanding.retransmitted && !outstanding.rttSampleTaken) {
                    sampleNanos = acknowledgedAtNanos - outstanding.firstSentNanos;
                }
                continue;
            }

            if (start.isBefore(acknowledgmentNumber)
                    && acknowledgmentNumber.isBefore(end)) {
                int acknowledgedPrefix = Math.toIntExact(
                        start.distanceTo(acknowledgmentNumber));
                if (!outstanding.retransmitted && !outstanding.rttSampleTaken) {
                    sampleNanos = acknowledgedAtNanos - outstanding.firstSentNanos;
                }
                retransmittedDataAcknowledged |= outstanding.retransmitted;
                byte[] remainingPayload = Arrays.copyOfRange(
                        first.payload(), acknowledgedPrefix, first.payloadLength());
                TcpSegment remaining = TcpChecksum.apply(
                        first.withSequenceNumber(acknowledgmentNumber.toLong())
                                .withPayload(remainingPayload));
                segments.removeFirst();
                outstanding.segment = remaining;
                outstanding.rttSampleTaken = true;
                segments.addFirst(outstanding);
                acknowledgedBytes += acknowledgedPrefix;
            }
            break;
        }

        bytesInFlight -= acknowledgedBytes;
        Optional<Duration> rttSample =
                retransmittedDataAcknowledged || sampleNanos == null || sampleNanos <= 0
                        ? Optional.empty()
                        : Optional.of(Duration.ofNanos(sampleNanos));
        return new AcknowledgmentResult(
                acknowledgedBytes, fullyAcknowledgedSegments, rttSample);
    }

    public TcpSegment retransmitEarliest(long sentAtNanos) {
        return retransmitEarliestDueToTimeout(sentAtNanos);
    }

    public TcpSegment retransmitEarliestDueToTimeout(long sentAtNanos) {
        OutstandingSegment earliest = earliest();
        earliest.timeoutCount++;
        markRetransmitted(earliest, sentAtNanos);
        return earliest.segment;
    }

    public TcpSegment retransmitEarliestFast(long sentAtNanos) {
        OutstandingSegment earliest = earliest();
        markRetransmitted(earliest, sentAtNanos);
        return earliest.segment;
    }

    public boolean earliestHasTimedOut() {
        return earliest().timeoutCount > 0;
    }

    private OutstandingSegment earliest() {
        OutstandingSegment earliest = segments.peekFirst();
        if (earliest == null) {
            throw new IllegalStateException("no outstanding segment to retransmit");
        }
        return earliest;
    }

    private static void markRetransmitted(
            OutstandingSegment earliest, long sentAtNanos) {
        earliest.retransmitted = true;
        earliest.lastSentNanos = sentAtNanos;
        earliest.transmissionCount++;
    }

    public long bytesInFlight() {
        return bytesInFlight;
    }

    public int segmentCount() {
        return segments.size();
    }

    public List<TcpSegment> segments() {
        return segments.stream().map(entry -> entry.segment).toList();
    }

    public record AcknowledgmentResult(
            long acknowledgedBytes,
            int fullyAcknowledgedSegments,
            Optional<Duration> rttSample) {
        public AcknowledgmentResult {
            Objects.requireNonNull(rttSample, "rttSample");
        }
    }

    private static final class OutstandingSegment {
        private TcpSegment segment;
        private final long firstSentNanos;
        private long lastSentNanos;
        private boolean retransmitted;
        private boolean rttSampleTaken;
        private int transmissionCount = 1;
        private int timeoutCount;

        private OutstandingSegment(TcpSegment segment, long sentAtNanos) {
            this.segment = segment;
            firstSentNanos = sentAtNanos;
            lastSentNanos = sentAtNanos;
        }
    }
}
