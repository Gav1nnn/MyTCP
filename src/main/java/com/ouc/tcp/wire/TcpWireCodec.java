package com.ouc.tcp.wire;

import com.ouc.tcp.core.TcpFlag;
import com.ouc.tcp.core.TcpSegment;

import java.net.Inet4Address;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Encodes and decodes the fixed portion of an RFC TCP header.
 *
 * <p>The IP addresses are supplied out of band because they belong to the IP
 * header, while {@link TcpSegment} retains them for connection validation and
 * pseudo-header checksum calculation. TCP options, ECN flags, and urgent data
 * are deliberately rejected until the protocol core can preserve their
 * semantics without a lossy conversion.</p>
 */
public final class TcpWireCodec {
    public static final int FIXED_HEADER_LENGTH = 20;
    private static final int FIXED_DATA_OFFSET_WORDS = FIXED_HEADER_LENGTH / 4;
    private static final int SUPPORTED_FLAGS_MASK = 0x3F;

    public byte[] encode(TcpSegment segment) {
        Objects.requireNonNull(segment, "segment");
        if (segment.hasFlag(TcpFlag.URG)) {
            throw new IllegalArgumentException(
                    "urgent data is not supported by the fixed-header codec");
        }

        byte[] payload = segment.payload();
        ByteBuffer bytes = ByteBuffer
                .allocate(Math.addExact(FIXED_HEADER_LENGTH, payload.length))
                .order(ByteOrder.BIG_ENDIAN);
        bytes.putShort((short) segment.sourcePort());
        bytes.putShort((short) segment.destinationPort());
        bytes.putInt((int) segment.sequenceNumber());
        bytes.putInt((int) segment.acknowledgmentNumber());
        bytes.put((byte) (FIXED_DATA_OFFSET_WORDS << 4));
        bytes.put((byte) segment.flagsMask());
        bytes.putShort((short) segment.advertisedWindow());
        bytes.putShort((short) segment.checksum());
        bytes.putShort((short) 0);
        bytes.put(payload);
        return bytes.array();
    }

    public TcpSegment decode(
            byte[] bytes,
            Inet4Address sourceAddress,
            Inet4Address destinationAddress) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(sourceAddress, "sourceAddress");
        Objects.requireNonNull(destinationAddress, "destinationAddress");
        if (bytes.length < FIXED_HEADER_LENGTH) {
            throw new IllegalArgumentException(
                    "TCP segment is shorter than the fixed header");
        }

        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int sourcePort = Short.toUnsignedInt(input.getShort());
        int destinationPort = Short.toUnsignedInt(input.getShort());
        long sequenceNumber = Integer.toUnsignedLong(input.getInt());
        long acknowledgmentNumber = Integer.toUnsignedLong(input.getInt());

        int offsetAndReserved = Byte.toUnsignedInt(input.get());
        int dataOffsetWords = offsetAndReserved >>> 4;
        if ((offsetAndReserved & 0x0F) != 0) {
            throw new IllegalArgumentException(
                    "reserved TCP header bits must be zero");
        }
        if (dataOffsetWords != FIXED_DATA_OFFSET_WORDS) {
            throw new IllegalArgumentException(
                    "TCP options are not supported by the fixed-header codec");
        }

        int flagsMask = Byte.toUnsignedInt(input.get());
        if ((flagsMask & ~SUPPORTED_FLAGS_MASK) != 0) {
            throw new IllegalArgumentException("ECN TCP flags are not supported");
        }
        Set<TcpFlag> flags = decodeFlags(flagsMask);
        if (flags.contains(TcpFlag.URG)) {
            throw new IllegalArgumentException(
                    "urgent data is not supported by the fixed-header codec");
        }

        int advertisedWindow = Short.toUnsignedInt(input.getShort());
        int checksum = Short.toUnsignedInt(input.getShort());
        int urgentPointer = Short.toUnsignedInt(input.getShort());
        if (urgentPointer != 0) {
            throw new IllegalArgumentException("urgent pointer must be zero");
        }

        byte[] payload = Arrays.copyOfRange(
                bytes, FIXED_HEADER_LENGTH, bytes.length);
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
                payload);
    }

    private static Set<TcpFlag> decodeFlags(int flagsMask) {
        EnumSet<TcpFlag> flags = EnumSet.noneOf(TcpFlag.class);
        for (TcpFlag flag : TcpFlag.values()) {
            if ((flagsMask & flag.mask()) != 0) {
                flags.add(flag);
            }
        }
        return flags;
    }
}
