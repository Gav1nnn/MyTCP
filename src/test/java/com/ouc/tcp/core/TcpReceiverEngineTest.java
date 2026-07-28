package com.ouc.tcp.core;

import com.ouc.tcp.checksum.TcpChecksum;
import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpReceiverEngineTest {
    @Test
    void deliversInOrderBytesAndAdvancesCumulativeAck() throws Exception {
        TcpReceiverEngine receiver = new TcpReceiverEngine(SequenceNumber32.of(100), 8);

        ReceiveResult result = receiver.receive(segment(100, new byte[] {1, 2, 3}));

        assertEquals(ReceiveDisposition.IN_ORDER, result.disposition());
        assertArrayEquals(new byte[] {1, 2, 3}, result.deliveredBytes());
        assertEquals(103, result.acknowledgmentNumber().toLong());
        assertEquals(8, result.advertisedWindow());
        assertTrue(result.acknowledgmentRequired());
    }

    @Test
    void buffersOutOfOrderBytesUntilGapArrives() throws Exception {
        TcpReceiverEngine receiver = new TcpReceiverEngine(SequenceNumber32.of(100), 8);

        ReceiveResult outOfOrder = receiver.receive(segment(103, new byte[] {4, 5}));
        assertEquals(ReceiveDisposition.OUT_OF_ORDER, outOfOrder.disposition());
        assertArrayEquals(new byte[0], outOfOrder.deliveredBytes());
        assertEquals(100, outOfOrder.acknowledgmentNumber().toLong());
        assertEquals(8, outOfOrder.advertisedWindow());

        ReceiveResult fillsGap = receiver.receive(segment(100, new byte[] {1, 2, 3}));
        assertEquals(ReceiveDisposition.IN_ORDER, fillsGap.disposition());
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, fillsGap.deliveredBytes());
        assertEquals(105, fillsGap.acknowledgmentNumber().toLong());
        assertEquals(0, receiver.bufferedByteCount());
    }

    @Test
    void neverDeliversDuplicateBytesTwice() throws Exception {
        TcpReceiverEngine receiver = new TcpReceiverEngine(SequenceNumber32.of(100), 8);
        receiver.receive(segment(100, new byte[] {1, 2, 3}));

        ReceiveResult duplicate = receiver.receive(segment(100, new byte[] {1, 2, 3}));

        assertEquals(ReceiveDisposition.DUPLICATE, duplicate.disposition());
        assertArrayEquals(new byte[0], duplicate.deliveredBytes());
        assertEquals(103, duplicate.acknowledgmentNumber().toLong());
        assertTrue(duplicate.acknowledgmentRequired());
    }

    @Test
    void retainsFirstCopyWhenSegmentsOverlap() throws Exception {
        TcpReceiverEngine receiver = new TcpReceiverEngine(SequenceNumber32.of(100), 8);
        receiver.receive(segment(102, new byte[] {9, 9, 5}));

        ReceiveResult result = receiver.receive(segment(100, new byte[] {1, 2, 3, 4}));

        assertArrayEquals(new byte[] {1, 2, 9, 9, 5}, result.deliveredBytes());
        assertEquals(105, result.acknowledgmentNumber().toLong());
    }

    @Test
    void trimsDataAtRightWindowEdge() throws Exception {
        TcpReceiverEngine receiver = new TcpReceiverEngine(SequenceNumber32.of(100), 4);

        ReceiveResult partial = receiver.receive(segment(102, new byte[] {3, 4, 5, 6}));
        assertEquals(ReceiveDisposition.OUT_OF_ORDER, partial.disposition());
        assertEquals(2, receiver.bufferedByteCount());

        ReceiveResult result = receiver.receive(segment(100, new byte[] {1, 2}));
        assertArrayEquals(new byte[] {1, 2, 3, 4}, result.deliveredBytes());
        assertEquals(104, result.acknowledgmentNumber().toLong());
    }

    @Test
    void acknowledgesSegmentOutsideReceiveWindowWithoutBufferingIt() throws Exception {
        TcpReceiverEngine receiver = new TcpReceiverEngine(SequenceNumber32.of(100), 4);

        ReceiveResult result = receiver.receive(segment(104, new byte[] {5}));

        assertEquals(ReceiveDisposition.OUTSIDE_WINDOW, result.disposition());
        assertEquals(100, result.acknowledgmentNumber().toLong());
        assertEquals(0, receiver.bufferedByteCount());
        assertTrue(result.acknowledgmentRequired());
    }

    @Test
    void rejectsSegmentThatCoversButDoesNotEndInsideReceiveWindow() throws Exception {
        TcpReceiverEngine receiver = new TcpReceiverEngine(SequenceNumber32.of(100), 4);

        ReceiveResult result =
                receiver.receive(segment(98, new byte[] {8, 9, 1, 2, 3, 4, 5, 6}));

        assertEquals(ReceiveDisposition.OUTSIDE_WINDOW, result.disposition());
        assertEquals(0, receiver.bufferedByteCount());
        assertEquals(100, result.acknowledgmentNumber().toLong());
    }

    @Test
    void reassemblesAcrossSequenceNumberWrap() throws Exception {
        TcpReceiverEngine receiver =
                new TcpReceiverEngine(SequenceNumber32.of(0xFFFF_FFFEL), 8);
        receiver.receive(segment(0, new byte[] {3, 4}));

        ReceiveResult result =
                receiver.receive(segment(0xFFFF_FFFEL, new byte[] {1, 2}));

        assertArrayEquals(new byte[] {1, 2, 3, 4}, result.deliveredBytes());
        assertEquals(2, result.acknowledgmentNumber().toLong());
    }

    @Test
    void silentlyDiscardsCorruptedSegment() throws Exception {
        TcpReceiverEngine receiver = new TcpReceiverEngine(SequenceNumber32.of(100), 8);
        TcpSegment valid = segment(100, new byte[] {1, 2, 3});
        TcpSegment corrupted = valid.withPayload(new byte[] {1, 2, 4});

        ReceiveResult result = receiver.receive(corrupted);

        assertEquals(ReceiveDisposition.CHECKSUM_FAILED, result.disposition());
        assertArrayEquals(new byte[0], result.deliveredBytes());
        assertEquals(100, result.acknowledgmentNumber().toLong());
        assertFalse(result.acknowledgmentRequired());
    }

    @Test
    void ignoresEmptySegmentOnDataPath() throws Exception {
        TcpReceiverEngine receiver = new TcpReceiverEngine(SequenceNumber32.of(100), 8);

        ReceiveResult result = receiver.receive(segment(100, new byte[0]));

        assertEquals(ReceiveDisposition.NO_DATA, result.disposition());
        assertFalse(result.acknowledgmentRequired());
        assertEquals(100, result.acknowledgmentNumber().toLong());
    }

    @Test
    void treatsMalformedOversizedSegmentAsChecksumFailure() throws Exception {
        TcpReceiverEngine receiver = new TcpReceiverEngine(SequenceNumber32.of(100), 8);
        TcpSegment oversized = new TcpSegment(
                ipv4("192.0.2.1"),
                ipv4("198.51.100.2"),
                19001,
                19002,
                100,
                0,
                Set.of(TcpFlag.ACK),
                8,
                0,
                new byte[0xFFFF - 20 + 1]);

        ReceiveResult result = receiver.receive(oversized);

        assertEquals(ReceiveDisposition.CHECKSUM_FAILED, result.disposition());
        assertFalse(result.acknowledgmentRequired());
    }

    @Test
    void resultPayloadIsDefensivelyCopied() throws Exception {
        TcpReceiverEngine receiver = new TcpReceiverEngine(SequenceNumber32.of(100), 8);
        ReceiveResult result = receiver.receive(segment(100, new byte[] {1, 2}));

        byte[] delivered = result.deliveredBytes();
        delivered[0] = 99;

        assertArrayEquals(new byte[] {1, 2}, result.deliveredBytes());
    }

    private static TcpSegment segment(long sequenceNumber, byte[] payload) throws Exception {
        return TcpChecksum.apply(new TcpSegment(
                ipv4("192.0.2.1"),
                ipv4("198.51.100.2"),
                19001,
                19002,
                sequenceNumber,
                0,
                Set.of(TcpFlag.ACK),
                8,
                0,
                payload));
    }

    private static Inet4Address ipv4(String address) throws Exception {
        return (Inet4Address) InetAddress.getByName(address);
    }
}
