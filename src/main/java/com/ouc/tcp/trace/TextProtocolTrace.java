package com.ouc.tcp.trace;

import com.ouc.tcp.connection.TcpState;
import com.ouc.tcp.core.TcpFlag;
import com.ouc.tcp.core.TcpSegment;

import java.io.PrintWriter;
import java.io.Writer;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Stable line-oriented trace suitable for CLI inspection and assertions.
 */
public final class TextProtocolTrace implements ProtocolTrace {
    private final PrintWriter output;

    public TextProtocolTrace(Writer output) {
        this.output = new PrintWriter(
                Objects.requireNonNull(output, "output"));
    }

    @Override
    public synchronized void segment(
            SegmentDirection direction,
            TcpSegment segment) {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(segment, "segment");
        output.printf(
                "event=segment direction=%s seq=%s ack=%s len=%d "
                        + "flags=%s rwnd=%d checksum=%d%n",
                direction,
                Long.toUnsignedString(segment.sequenceNumber()),
                Long.toUnsignedString(segment.acknowledgmentNumber()),
                segment.payloadLength(),
                flags(segment),
                segment.advertisedWindow(),
                segment.checksum());
        output.flush();
    }

    @Override
    public synchronized void stateTransition(
            TcpState previous,
            TcpState current) {
        output.printf(
                "event=state from=%s to=%s%n",
                Objects.requireNonNull(previous, "previous"),
                Objects.requireNonNull(current, "current"));
        output.flush();
    }

    @Override
    public synchronized void senderSnapshot(
            TcpState state,
            SenderSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        output.printf(
                "event=sender state=%s snd_una=%s snd_nxt=%s "
                        + "flight=%d cwnd=%d ssthresh=%d rwnd=%d rto_ms=%d%n",
                Objects.requireNonNull(state, "state"),
                snapshot.sendUnacknowledged(),
                snapshot.sendNext(),
                snapshot.flightSize(),
                snapshot.congestionWindow(),
                snapshot.slowStartThreshold(),
                snapshot.sendWindow(),
                snapshot.retransmissionTimeout().toMillis());
        output.flush();
    }

    @Override
    public synchronized void close() {
        output.close();
    }

    private static String flags(TcpSegment segment) {
        String flags = segment.flags().stream()
                .sorted()
                .map(TcpFlag::name)
                .collect(Collectors.joining(","));
        return flags.isEmpty() ? "-" : flags;
    }
}
