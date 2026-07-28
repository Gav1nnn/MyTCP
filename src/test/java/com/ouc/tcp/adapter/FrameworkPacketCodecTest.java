package com.ouc.tcp.adapter;

import com.ouc.tcp.checksum.TcpChecksum;
import com.ouc.tcp.core.TcpFlag;
import com.ouc.tcp.core.TcpSegment;
import com.ouc.tcp.message.TCP_PACKET;
import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameworkPacketCodecTest {
    private final FrameworkPacketCodec codec = new FrameworkPacketCodec();

    @Test
    void roundTripsDataSegmentIncludingUnsignedHeaderFields() throws Exception {
        TcpSegment original = TcpChecksum.apply(new TcpSegment(
                ipv4("192.0.2.1"),
                ipv4("198.51.100.2"),
                50_001,
                50_002,
                0xFFFF_FFFEL,
                0x8000_0001L,
                Set.of(TcpFlag.ACK, TcpFlag.PSH),
                60_000,
                0,
                IntegerPayloadCodec.encode(new int[] {1, -2, Integer.MAX_VALUE})));

        TCP_PACKET frameworkPacket = codec.encode(original);
        TcpSegment decoded = codec.decode(frameworkPacket);

        assertEquals(original, decoded);
        assertEquals((byte) 7, frameworkPacket.getTcpH().getTh_eflag());
        assertTrue(TcpChecksum.isValid(decoded));
    }

    @Test
    void roundTripsEmptyAcknowledgment() throws Exception {
        TcpSegment acknowledgment = TcpChecksum.apply(new TcpSegment(
                ipv4("198.51.100.2"),
                ipv4("192.0.2.1"),
                19002,
                19001,
                500,
                900,
                Set.of(TcpFlag.ACK),
                32_768,
                0,
                new byte[0]));

        assertEquals(acknowledgment, codec.decode(codec.encode(acknowledgment)));
    }

    @Test
    void rejectsPayloadThatFrameworkCannotRepresentLosslessly()
            throws Exception {
        TcpSegment segment = new TcpSegment(
                ipv4("192.0.2.1"),
                ipv4("198.51.100.2"),
                19001,
                19002,
                1,
                1,
                Set.of(TcpFlag.ACK),
                1000,
                0,
                new byte[] {1, 2, 3});

        assertThrows(IllegalArgumentException.class, () -> codec.encode(segment));
    }

    @Test
    void frameworkMutationIsDetectedByCoreChecksum() throws Exception {
        TcpSegment original = TcpChecksum.apply(new TcpSegment(
                ipv4("192.0.2.1"),
                ipv4("198.51.100.2"),
                19001,
                19002,
                1,
                1,
                Set.of(TcpFlag.ACK),
                1000,
                0,
                IntegerPayloadCodec.encode(new int[] {10, 20})));
        TCP_PACKET packet = codec.encode(original);
        packet.getTcpS().setDataByIndex(0, 99);

        assertTrue(!TcpChecksum.isValid(codec.decode(packet)));
    }

    private static Inet4Address ipv4(String address) throws Exception {
        return (Inet4Address) InetAddress.getByName(address);
    }
}
