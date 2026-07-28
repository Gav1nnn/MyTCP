package com.ouc.tcp.core;

import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpSegmentTest {
    @Test
    void protectsPayloadAndFlagsFromExternalMutation() throws Exception {
        byte[] payload = {1, 2, 3};
        Set<TcpFlag> flags = EnumSet.of(TcpFlag.ACK);
        TcpSegment segment = segment(flags, payload);

        payload[0] = 99;
        flags.add(TcpFlag.FIN);
        byte[] returnedPayload = segment.payload();
        returnedPayload[1] = 88;

        assertArrayEquals(new byte[] {1, 2, 3}, segment.payload());
        assertEquals(Set.of(TcpFlag.ACK), segment.flags());
        assertThrows(UnsupportedOperationException.class, () -> segment.flags().add(TcpFlag.SYN));
    }

    @Test
    void reportsFlagsAndSequenceSpaceLength() throws Exception {
        TcpSegment segment = segment(
                EnumSet.of(TcpFlag.SYN, TcpFlag.ACK, TcpFlag.FIN),
                new byte[] {10, 20, 30});

        assertTrue(segment.hasFlag(TcpFlag.ACK));
        assertFalse(segment.hasFlag(TcpFlag.RST));
        assertEquals(TcpFlag.SYN.mask() | TcpFlag.ACK.mask() | TcpFlag.FIN.mask(),
                segment.flagsMask());
        assertEquals(5, segment.sequenceSpaceLength());
    }

    @Test
    void validatesUnsignedHeaderFields() throws Exception {
        Inet4Address address = ipv4("192.0.2.1");

        assertThrows(IllegalArgumentException.class, () -> new TcpSegment(
                address,
                address,
                -1,
                80,
                0,
                0,
                Set.of(),
                0,
                0,
                new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new TcpSegment(
                address,
                address,
                80,
                80,
                TcpSegment.MAX_SEQUENCE_NUMBER + 1,
                0,
                Set.of(),
                0,
                0,
                new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new TcpSegment(
                address,
                address,
                80,
                80,
                0,
                0,
                Set.of(),
                TcpSegment.MAX_WINDOW + 1,
                0,
                new byte[0]));
    }

    @Test
    void createsModifiedCopiesWithoutChangingOriginal() throws Exception {
        TcpSegment original = segment(Set.of(TcpFlag.ACK), new byte[] {1, 2});

        TcpSegment changedPayload = original.withPayload(new byte[] {7, 8});
        TcpSegment changedChecksum = original.withChecksum(0xABCD);

        assertArrayEquals(new byte[] {1, 2}, original.payload());
        assertEquals(0, original.checksum());
        assertArrayEquals(new byte[] {7, 8}, changedPayload.payload());
        assertEquals(0xABCD, changedChecksum.checksum());
    }

    private static TcpSegment segment(Set<TcpFlag> flags, byte[] payload) throws Exception {
        return new TcpSegment(
                ipv4("192.0.2.10"),
                ipv4("192.0.2.20"),
                19001,
                19002,
                0xFFFF_FFF0L,
                42,
                flags,
                4096,
                0,
                payload);
    }

    private static Inet4Address ipv4(String address) throws Exception {
        return (Inet4Address) InetAddress.getByName(address);
    }
}
