package com.ouc.tcp.checksum;

import com.ouc.tcp.core.TcpFlag;
import com.ouc.tcp.core.TcpSegment;
import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpChecksumTest {
    @Test
    void matchesFixedIpv4TcpChecksumVector() throws Exception {
        TcpSegment segment = segment(
                0x1122_3344L,
                0x5566_7788L,
                Set.of(TcpFlag.ACK, TcpFlag.PSH),
                4096,
                "hello".getBytes(StandardCharsets.US_ASCII));

        assertEquals(0x2DE0, TcpChecksum.compute(segment));
    }

    @Test
    void appliesAndValidatesChecksum() throws Exception {
        TcpSegment protectedSegment = TcpChecksum.apply(segment(
                100,
                200,
                Set.of(TcpFlag.ACK),
                8192,
                new byte[] {1, 2, 3}));

        assertTrue(TcpChecksum.isValid(protectedSegment));
        assertFalse(TcpChecksum.isValid(
                protectedSegment.withPayload(new byte[] {1, 2, 4})));
    }

    @Test
    void detectsHeaderCorruption() throws Exception {
        TcpSegment protectedSegment = TcpChecksum.apply(segment(
                100,
                200,
                Set.of(TcpFlag.ACK),
                8192,
                new byte[] {1, 2, 3, 4}));
        TcpSegment changedSequence = new TcpSegment(
                protectedSegment.sourceAddress(),
                protectedSegment.destinationAddress(),
                protectedSegment.sourcePort(),
                protectedSegment.destinationPort(),
                101,
                protectedSegment.acknowledgmentNumber(),
                protectedSegment.flags(),
                protectedSegment.advertisedWindow(),
                protectedSegment.checksum(),
                protectedSegment.payload());

        assertFalse(TcpChecksum.isValid(changedSequence));
    }

    @Test
    void validatesAckOnlySegment() throws Exception {
        TcpSegment acknowledgment = TcpChecksum.apply(segment(
                500,
                900,
                Set.of(TcpFlag.ACK),
                2048,
                new byte[0]));

        assertTrue(TcpChecksum.isValid(acknowledgment));
    }

    @Test
    void rejectsPayloadThatCannotFitIpv4TcpLength() throws Exception {
        TcpSegment oversized = segment(
                0,
                0,
                Set.of(TcpFlag.ACK),
                0,
                new byte[0xFFFF - 20 + 1]);

        assertThrows(IllegalArgumentException.class, () -> TcpChecksum.compute(oversized));
    }

    private static TcpSegment segment(
            long sequenceNumber,
            long acknowledgmentNumber,
            Set<TcpFlag> flags,
            int window,
            byte[] payload) throws Exception {
        return new TcpSegment(
                ipv4("192.0.2.1"),
                ipv4("198.51.100.2"),
                12345,
                80,
                sequenceNumber,
                acknowledgmentNumber,
                flags,
                window,
                0,
                payload);
    }

    private static Inet4Address ipv4(String address) throws Exception {
        return (Inet4Address) InetAddress.getByName(address);
    }
}
