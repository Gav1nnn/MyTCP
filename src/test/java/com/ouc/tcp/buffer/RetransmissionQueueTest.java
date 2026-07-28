package com.ouc.tcp.buffer;

import com.ouc.tcp.checksum.TcpChecksum;
import com.ouc.tcp.core.SequenceNumber32;
import com.ouc.tcp.core.TcpFlag;
import com.ouc.tcp.core.TcpSegment;
import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetransmissionQueueTest {
    @Test
    void removesEverySegmentCoveredByCumulativeAck() throws Exception {
        RetransmissionQueue queue = new RetransmissionQueue();
        queue.add(segment(100, new byte[] {1, 2, 3}));
        queue.add(segment(103, new byte[] {4, 5}));

        RetransmissionQueue.AcknowledgmentResult result =
                queue.acknowledge(SequenceNumber32.of(105));

        assertEquals(5, result.acknowledgedBytes());
        assertEquals(2, result.fullyAcknowledgedSegments());
        assertEquals(0, queue.bytesInFlight());
        assertEquals(0, queue.segmentCount());
    }

    @Test
    void trimsPartiallyAcknowledgedHeadAndRecomputesChecksum() throws Exception {
        RetransmissionQueue queue = new RetransmissionQueue();
        queue.add(segment(100, new byte[] {1, 2, 3, 4}));

        RetransmissionQueue.AcknowledgmentResult result =
                queue.acknowledge(SequenceNumber32.of(102));
        TcpSegment remaining = queue.segments().get(0);

        assertEquals(2, result.acknowledgedBytes());
        assertEquals(0, result.fullyAcknowledgedSegments());
        assertEquals(102, remaining.sequenceNumber());
        assertArrayEquals(new byte[] {3, 4}, remaining.payload());
        assertTrue(TcpChecksum.isValid(remaining));
        assertEquals(2, queue.bytesInFlight());
    }

    @Test
    void handlesCumulativeAckAcrossSequenceWrap() throws Exception {
        RetransmissionQueue queue = new RetransmissionQueue();
        queue.add(segment(0xFFFF_FFFEL, new byte[] {1, 2, 3, 4}));

        RetransmissionQueue.AcknowledgmentResult result =
                queue.acknowledge(SequenceNumber32.of(2));

        assertEquals(4, result.acknowledgedBytes());
        assertEquals(0, queue.segmentCount());
    }

    @Test
    void rejectsGapBetweenQueuedSegments() throws Exception {
        RetransmissionQueue queue = new RetransmissionQueue();
        queue.add(segment(100, new byte[] {1, 2}));

        assertThrows(
                IllegalArgumentException.class,
                () -> queue.add(segment(103, new byte[] {4})));
    }

    private static TcpSegment segment(long sequenceNumber, byte[] payload) throws Exception {
        return TcpChecksum.apply(new TcpSegment(
                ipv4("192.0.2.1"),
                ipv4("198.51.100.2"),
                19001,
                19002,
                sequenceNumber,
                500,
                Set.of(TcpFlag.ACK),
                4096,
                0,
                payload));
    }

    private static Inet4Address ipv4(String address) throws Exception {
        return (Inet4Address) InetAddress.getByName(address);
    }
}
