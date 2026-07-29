package com.ouc.tcp.wire;

import com.ouc.tcp.checksum.TcpChecksum;
import com.ouc.tcp.core.TcpFlag;
import com.ouc.tcp.core.TcpSegment;
import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpWireCodecTest {
    private final TcpWireCodec codec = new TcpWireCodec();

    @Test
    void writesRfcHeaderInNetworkByteOrder() throws Exception {
        TcpSegment segment = new TcpSegment(
                ipv4("192.0.2.1"),
                ipv4("198.51.100.2"),
                0x1234,
                0xABCD,
                0x1122_3344L,
                0xA1B2_C3D4L,
                Set.of(TcpFlag.ACK, TcpFlag.PSH),
                0x5678,
                0x9ABC,
                new byte[] {1, 2, 3});

        byte[] encoded = codec.encode(segment);

        ByteBuffer expected = ByteBuffer.allocate(23).order(ByteOrder.BIG_ENDIAN);
        expected.putShort((short) 0x1234);
        expected.putShort((short) 0xABCD);
        expected.putInt(0x1122_3344);
        expected.putInt(0xA1B2_C3D4);
        expected.put((byte) 0x50);
        expected.put((byte) 0x18);
        expected.putShort((short) 0x5678);
        expected.putShort((short) 0x9ABC);
        expected.putShort((short) 0);
        expected.put(new byte[] {1, 2, 3});
        assertArrayEquals(expected.array(), encoded);
    }

    @Test
    void roundTripsUnsignedFieldsAndValidChecksum() throws Exception {
        TcpSegment original = TcpChecksum.apply(new TcpSegment(
                ipv4("192.0.2.1"),
                ipv4("198.51.100.2"),
                60_001,
                60_002,
                0xFFFF_FFFEL,
                0x8000_0001L,
                Set.of(TcpFlag.SYN, TcpFlag.ACK),
                60_000,
                0,
                new byte[] {10, 20, 30, 40, 50}));

        TcpSegment decoded = codec.decode(
                codec.encode(original),
                original.sourceAddress(),
                original.destinationAddress());

        assertEquals(original, decoded);
        assertTrue(TcpChecksum.isValid(decoded));
    }

    @Test
    void checksumDetectsWirePayloadCorruption() throws Exception {
        TcpSegment original = TcpChecksum.apply(new TcpSegment(
                ipv4("192.0.2.1"),
                ipv4("198.51.100.2"),
                19001,
                19002,
                1,
                1,
                Set.of(TcpFlag.ACK),
                4096,
                0,
                new byte[] {1, 2, 3, 4}));
        byte[] encoded = codec.encode(original);
        encoded[encoded.length - 1] ^= 0x01;

        TcpSegment corrupted = codec.decode(
                encoded, original.sourceAddress(), original.destinationAddress());

        assertFalse(TcpChecksum.isValid(corrupted));
    }

    @Test
    void rejectsMalformedOrUnsupportedHeaders() throws Exception {
        Inet4Address source = ipv4("192.0.2.1");
        Inet4Address destination = ipv4("198.51.100.2");

        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(new byte[19], source, destination));

        byte[] optionsHeader = fixedHeader();
        optionsHeader[12] = 0x60;
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(optionsHeader, source, destination));

        byte[] reservedBits = fixedHeader();
        reservedBits[12] = 0x51;
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(reservedBits, source, destination));

        byte[] ecnFlags = fixedHeader();
        ecnFlags[13] = (byte) 0x80;
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(ecnFlags, source, destination));

        byte[] urgentPointer = fixedHeader();
        urgentPointer[18] = 0x01;
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(urgentPointer, source, destination));
    }

    @Test
    void rejectsUrgentFlagUntilUrgentPointerIsModeled() throws Exception {
        TcpSegment urgent = new TcpSegment(
                ipv4("192.0.2.1"),
                ipv4("198.51.100.2"),
                19001,
                19002,
                1,
                1,
                Set.of(TcpFlag.URG),
                4096,
                0,
                new byte[0]);

        assertThrows(IllegalArgumentException.class, () -> codec.encode(urgent));
    }

    private static byte[] fixedHeader() {
        byte[] header = new byte[TcpWireCodec.FIXED_HEADER_LENGTH];
        header[12] = 0x50;
        return header;
    }

    private static Inet4Address ipv4(String address) throws Exception {
        return (Inet4Address) InetAddress.getByName(address);
    }
}
