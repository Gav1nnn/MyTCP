package com.ouc.tcp.checksum;

import com.ouc.tcp.core.TcpSegment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Computes the TCP 16-bit one's-complement checksum for IPv4.
 */
public final class TcpChecksum {
    private static final int IPV4_PSEUDO_HEADER_LENGTH = 12;
    private static final int TCP_HEADER_LENGTH = 20;
    private static final int TCP_PROTOCOL_NUMBER = 6;

    private TcpChecksum() {
    }

    /**
     * Computes the checksum with the segment checksum field treated as zero.
     */
    public static int compute(TcpSegment segment) {
        Objects.requireNonNull(segment, "segment");
        byte[] payload = segment.payload();
        int tcpLength = Math.addExact(TCP_HEADER_LENGTH, payload.length);
        if (tcpLength > 0xFFFF) {
            throw new IllegalArgumentException(
                    "TCP header and payload exceed the IPv4 length field");
        }

        ByteBuffer bytes = ByteBuffer
                .allocate(IPV4_PSEUDO_HEADER_LENGTH + tcpLength)
                .order(ByteOrder.BIG_ENDIAN);

        bytes.put(segment.sourceAddress().getAddress());
        bytes.put(segment.destinationAddress().getAddress());
        bytes.put((byte) 0);
        bytes.put((byte) TCP_PROTOCOL_NUMBER);
        bytes.putShort((short) tcpLength);

        bytes.putShort((short) segment.sourcePort());
        bytes.putShort((short) segment.destinationPort());
        bytes.putInt((int) segment.sequenceNumber());
        bytes.putInt((int) segment.acknowledgmentNumber());
        bytes.put((byte) (5 << 4));
        bytes.put((byte) segment.flagsMask());
        bytes.putShort((short) segment.advertisedWindow());
        bytes.putShort((short) 0);
        bytes.putShort((short) 0);
        bytes.put(payload);

        return onesComplement(bytes.array());
    }

    public static TcpSegment apply(TcpSegment segment) {
        return Objects.requireNonNull(segment, "segment").withChecksum(compute(segment));
    }

    public static boolean isValid(TcpSegment segment) {
        Objects.requireNonNull(segment, "segment");
        return segment.checksum() == compute(segment);
    }

    private static int onesComplement(byte[] bytes) {
        long sum = 0;
        for (int index = 0; index < bytes.length; index += 2) {
            int high = Byte.toUnsignedInt(bytes[index]);
            int low = index + 1 < bytes.length ? Byte.toUnsignedInt(bytes[index + 1]) : 0;
            sum += (high << 8) | low;
            sum = (sum & 0xFFFF) + (sum >>> 16);
        }

        while ((sum >>> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >>> 16);
        }
        return (int) (~sum) & 0xFFFF;
    }
}
