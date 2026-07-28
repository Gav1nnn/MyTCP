package com.ouc.tcp.adapter;

import com.ouc.tcp.core.TcpFlag;
import com.ouc.tcp.core.TcpSegment;
import com.ouc.tcp.message.TCP_HEADER;
import com.ouc.tcp.message.TCP_PACKET;
import com.ouc.tcp.message.TCP_SEGMENT;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Lossless boundary conversion between framework packets and protocol-core
 * segments.
 */
public final class FrameworkPacketCodec {
    private static final byte DATA_OFFSET_WORDS = 5;
    private static final byte FRAMEWORK_ERROR_CONTROL = 7;

    public TcpSegment decode(TCP_PACKET packet) {
        Objects.requireNonNull(packet, "packet");
        TCP_HEADER header = Objects.requireNonNull(packet.getTcpH(), "packet.tcpH");
        TCP_SEGMENT body = Objects.requireNonNull(packet.getTcpS(), "packet.tcpS");

        return new TcpSegment(
                ipv4(packet.getSourceAddr(), "sourceAddr"),
                ipv4(packet.getDestinAddr(), "destinAddr"),
                Short.toUnsignedInt(header.getTh_sport()),
                Short.toUnsignedInt(header.getTh_dport()),
                Integer.toUnsignedLong(header.getTh_seq()),
                Integer.toUnsignedLong(header.getTh_ack()),
                decodeFlags(header),
                Short.toUnsignedInt(header.getTh_win()),
                Short.toUnsignedInt(header.getTh_sum()),
                IntegerPayloadCodec.encode(body.getData()));
    }

    public TCP_PACKET encode(TcpSegment segment) {
        Objects.requireNonNull(segment, "segment");
        if (segment.payloadLength() % IntegerPayloadCodec.BYTES_PER_INTEGER != 0) {
            throw new IllegalArgumentException(
                    "framework payload length must be a multiple of four bytes");
        }

        TCP_HEADER header = new TCP_HEADER(
                (short) segment.sourcePort(),
                (short) segment.destinationPort(),
                (int) segment.sequenceNumber(),
                (int) segment.acknowledgmentNumber(),
                DATA_OFFSET_WORDS,
                encodeFlags(segment.flags()),
                (short) segment.advertisedWindow(),
                (short) 0,
                (short) 0,
                (byte) 0,
                FRAMEWORK_ERROR_CONTROL);
        header.setTh_sum((short) segment.checksum());

        TCP_PACKET packet = new TCP_PACKET(
                header,
                new TCP_SEGMENT(IntegerPayloadCodec.decode(segment.payload())),
                segment.destinationAddress());
        packet.setSourceAddr(segment.sourceAddress());
        packet.setDestinAddr(segment.destinationAddress());
        return packet;
    }

    private static Set<TcpFlag> decodeFlags(TCP_HEADER header) {
        EnumSet<TcpFlag> flags = EnumSet.noneOf(TcpFlag.class);
        addIfSet(flags, TcpFlag.URG, header.getTh_flags_URG());
        addIfSet(flags, TcpFlag.ACK, header.getTh_flags_ACK());
        addIfSet(flags, TcpFlag.PSH, header.getTh_flags_PSH());
        addIfSet(flags, TcpFlag.RST, header.getTh_flags_RST());
        addIfSet(flags, TcpFlag.SYN, header.getTh_flags_SYN());
        addIfSet(flags, TcpFlag.FIN, header.getTh_flags_FIN());
        return flags;
    }

    private static String encodeFlags(Set<TcpFlag> flags) {
        return new String(new char[] {
            bit(flags, TcpFlag.URG),
            bit(flags, TcpFlag.ACK),
            bit(flags, TcpFlag.PSH),
            bit(flags, TcpFlag.RST),
            bit(flags, TcpFlag.SYN),
            bit(flags, TcpFlag.FIN)
        });
    }

    private static void addIfSet(
            Set<TcpFlag> flags, TcpFlag flag, boolean set) {
        if (set) {
            flags.add(flag);
        }
    }

    private static char bit(Set<TcpFlag> flags, TcpFlag flag) {
        return flags.contains(flag) ? '1' : '0';
    }

    private static Inet4Address ipv4(InetAddress address, String name) {
        Objects.requireNonNull(address, name);
        if (!(address instanceof Inet4Address ipv4Address)) {
            throw new IllegalArgumentException(name + " must be an IPv4 address");
        }
        return ipv4Address;
    }
}
