package com.ouc.tcp.core;

import java.net.Inet4Address;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable representation of a TCP segment used by the protocol core.
 *
 * <p>The sequence and acknowledgment fields are stored as unsigned 32-bit
 * values in Java {@code long}s. Payload access is defensive so a segment
 * cannot be changed after it has entered protocol state.</p>
 */
public final class TcpSegment {
    public static final long MAX_SEQUENCE_NUMBER = 0xFFFF_FFFFL;
    public static final int MAX_PORT = 0xFFFF;
    public static final int MAX_WINDOW = 0xFFFF;
    public static final int MAX_CHECKSUM = 0xFFFF;

    private final Inet4Address sourceAddress;
    private final Inet4Address destinationAddress;
    private final int sourcePort;
    private final int destinationPort;
    private final long sequenceNumber;
    private final long acknowledgmentNumber;
    private final Set<TcpFlag> flags;
    private final int advertisedWindow;
    private final int checksum;
    private final byte[] payload;

    public TcpSegment(
            Inet4Address sourceAddress,
            Inet4Address destinationAddress,
            int sourcePort,
            int destinationPort,
            long sequenceNumber,
            long acknowledgmentNumber,
            Set<TcpFlag> flags,
            int advertisedWindow,
            int checksum,
            byte[] payload) {
        this.sourceAddress = Objects.requireNonNull(sourceAddress, "sourceAddress");
        this.destinationAddress =
                Objects.requireNonNull(destinationAddress, "destinationAddress");
        this.sourcePort = requireUnsigned16(sourcePort, "sourcePort");
        this.destinationPort = requireUnsigned16(destinationPort, "destinationPort");
        this.sequenceNumber = requireUnsigned32(sequenceNumber, "sequenceNumber");
        this.acknowledgmentNumber =
                requireUnsigned32(acknowledgmentNumber, "acknowledgmentNumber");
        this.flags = immutableFlags(flags);
        this.advertisedWindow = requireUnsigned16(advertisedWindow, "advertisedWindow");
        this.checksum = requireUnsigned16(checksum, "checksum");
        this.payload = Objects.requireNonNull(payload, "payload").clone();
    }

    public Inet4Address sourceAddress() {
        return sourceAddress;
    }

    public Inet4Address destinationAddress() {
        return destinationAddress;
    }

    public int sourcePort() {
        return sourcePort;
    }

    public int destinationPort() {
        return destinationPort;
    }

    public long sequenceNumber() {
        return sequenceNumber;
    }

    public long acknowledgmentNumber() {
        return acknowledgmentNumber;
    }

    public Set<TcpFlag> flags() {
        return flags;
    }

    public boolean hasFlag(TcpFlag flag) {
        return flags.contains(flag);
    }

    public int flagsMask() {
        return flags.stream().mapToInt(TcpFlag::mask).reduce(0, (left, right) -> left | right);
    }

    public int advertisedWindow() {
        return advertisedWindow;
    }

    public int checksum() {
        return checksum;
    }

    public byte[] payload() {
        return payload.clone();
    }

    public int payloadLength() {
        return payload.length;
    }

    /**
     * Number of sequence-space bytes occupied by this segment.
     */
    public int sequenceSpaceLength() {
        int controlBytes = (hasFlag(TcpFlag.SYN) ? 1 : 0) + (hasFlag(TcpFlag.FIN) ? 1 : 0);
        return Math.addExact(payload.length, controlBytes);
    }

    public TcpSegment withPayload(byte[] replacementPayload) {
        return new TcpSegment(
                sourceAddress,
                destinationAddress,
                sourcePort,
                destinationPort,
                sequenceNumber,
                acknowledgmentNumber,
                flags,
                advertisedWindow,
                checksum,
                replacementPayload);
    }

    public TcpSegment withSequenceNumber(long replacementSequenceNumber) {
        return new TcpSegment(
                sourceAddress,
                destinationAddress,
                sourcePort,
                destinationPort,
                replacementSequenceNumber,
                acknowledgmentNumber,
                flags,
                advertisedWindow,
                checksum,
                payload);
    }

    public TcpSegment withAcknowledgmentNumber(
            long replacementAcknowledgmentNumber) {
        return new TcpSegment(
                sourceAddress,
                destinationAddress,
                sourcePort,
                destinationPort,
                sequenceNumber,
                replacementAcknowledgmentNumber,
                flags,
                advertisedWindow,
                checksum,
                payload);
    }

    public TcpSegment withAdvertisedWindow(int replacementAdvertisedWindow) {
        return new TcpSegment(
                sourceAddress,
                destinationAddress,
                sourcePort,
                destinationPort,
                sequenceNumber,
                acknowledgmentNumber,
                flags,
                replacementAdvertisedWindow,
                checksum,
                payload);
    }

    public TcpSegment withChecksum(int replacementChecksum) {
        return new TcpSegment(
                sourceAddress,
                destinationAddress,
                sourcePort,
                destinationPort,
                sequenceNumber,
                acknowledgmentNumber,
                flags,
                advertisedWindow,
                replacementChecksum,
                payload);
    }

    private static Set<TcpFlag> immutableFlags(Set<TcpFlag> flags) {
        Objects.requireNonNull(flags, "flags");
        if (flags.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(flags));
    }

    private static int requireUnsigned16(int value, String name) {
        if (value < 0 || value > MAX_PORT) {
            throw new IllegalArgumentException(name + " must be an unsigned 16-bit value");
        }
        return value;
    }

    private static long requireUnsigned32(long value, String name) {
        if (value < 0 || value > MAX_SEQUENCE_NUMBER) {
            throw new IllegalArgumentException(name + " must be an unsigned 32-bit value");
        }
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TcpSegment that)) {
            return false;
        }
        return sourcePort == that.sourcePort
                && destinationPort == that.destinationPort
                && sequenceNumber == that.sequenceNumber
                && acknowledgmentNumber == that.acknowledgmentNumber
                && advertisedWindow == that.advertisedWindow
                && checksum == that.checksum
                && sourceAddress.equals(that.sourceAddress)
                && destinationAddress.equals(that.destinationAddress)
                && flags.equals(that.flags)
                && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                sourceAddress,
                destinationAddress,
                sourcePort,
                destinationPort,
                sequenceNumber,
                acknowledgmentNumber,
                flags,
                advertisedWindow,
                checksum);
        return 31 * result + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "TcpSegment{"
                + "source=" + sourceAddress.getHostAddress() + ':' + sourcePort
                + ", destination=" + destinationAddress.getHostAddress() + ':' + destinationPort
                + ", sequenceNumber=" + sequenceNumber
                + ", acknowledgmentNumber=" + acknowledgmentNumber
                + ", flags=" + flags
                + ", advertisedWindow=" + advertisedWindow
                + ", checksum=" + checksum
                + ", payloadLength=" + payload.length
                + '}';
    }
}
