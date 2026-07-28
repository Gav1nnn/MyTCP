package com.ouc.tcp.core;

import com.ouc.tcp.checksum.TcpChecksum;
import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpSenderEngineTest {
    @Test
    void segmentsDataBySmssAndEffectiveWindow() throws Exception {
        TcpSenderEngine sender = sender(100, 6, 10, 4);

        List<TcpSegment> transmissions =
                sender.queueData(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10});

        assertEquals(2, transmissions.size());
        assertSegment(transmissions.get(0), 100, new byte[] {1, 2, 3, 4});
        assertSegment(transmissions.get(1), 104, new byte[] {5, 6});
        assertEquals(106, sender.sendNext().toLong());
        assertEquals(100, sender.sendUnacknowledged().toLong());
        assertEquals(6, sender.flightSize());
        assertEquals(4, sender.pendingByteCount());
    }

    @Test
    void congestionWindowCanBeSmallerThanReceiverWindow() throws Exception {
        TcpSenderEngine sender = sender(100, 10, 3, 4);

        List<TcpSegment> transmissions =
                sender.queueData(new byte[] {1, 2, 3, 4, 5});

        assertEquals(1, transmissions.size());
        assertSegment(transmissions.get(0), 100, new byte[] {1, 2, 3});
        assertEquals(2, sender.pendingByteCount());
    }

    @Test
    void cumulativeAckReleasesWindowAndSendsPendingData() throws Exception {
        TcpSenderEngine sender = sender(100, 6, 10, 4);
        sender.queueData(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10});

        AckProcessingResult result =
                sender.receiveAcknowledgment(ack(500, 106, 6));

        assertEquals(AckDisposition.NEW_ACK, result.disposition());
        assertEquals(6, result.newlyAcknowledgedBytes());
        assertEquals(106, sender.sendUnacknowledged().toLong());
        assertEquals(1, result.transmissions().size());
        assertSegment(result.transmissions().get(0), 106, new byte[] {7, 8, 9, 10});
        assertEquals(4, sender.flightSize());
    }

    @Test
    void partialAckTrimsOldestOutstandingSegment() throws Exception {
        TcpSenderEngine sender = sender(100, 8, 8, 4);
        sender.queueData(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});

        AckProcessingResult result =
                sender.receiveAcknowledgment(ack(500, 102, 8));
        TcpSegment firstOutstanding = sender.outstandingSegments().get(0);

        assertEquals(2, result.newlyAcknowledgedBytes());
        assertEquals(102, sender.sendUnacknowledged().toLong());
        assertEquals(102, firstOutstanding.sequenceNumber());
        assertArrayEquals(new byte[] {3, 4}, firstOutstanding.payload());
        assertTrue(TcpChecksum.isValid(firstOutstanding));
        assertEquals(6, sender.flightSize());
    }

    @Test
    void futureAckCannotReleaseUnsentData() throws Exception {
        TcpSenderEngine sender = sender(100, 4, 4, 4);
        sender.queueData(new byte[] {1, 2, 3, 4});

        AckProcessingResult result =
                sender.receiveAcknowledgment(ack(500, 105, 4));

        assertEquals(AckDisposition.FUTURE_ACK, result.disposition());
        assertEquals(100, sender.sendUnacknowledged().toLong());
        assertEquals(4, sender.flightSize());
        assertEquals(1, sender.outstandingSegments().size());
    }

    @Test
    void zeroWindowPausesAndWindowUpdateResumesTransmission() throws Exception {
        TcpSenderEngine sender = sender(100, 0, 10, 4);
        assertEquals(
                List.of(),
                sender.queueData(new byte[] {1, 2, 3, 4, 5, 6}));

        AckProcessingResult result =
                sender.receiveAcknowledgment(ack(500, 100, 4));

        assertEquals(AckDisposition.DUPLICATE_ACK, result.disposition());
        assertTrue(result.windowChanged());
        assertEquals(1, result.transmissions().size());
        assertSegment(result.transmissions().get(0), 100, new byte[] {1, 2, 3, 4});
        assertEquals(2, sender.pendingByteCount());
    }

    @Test
    void staleWindowUpdateIsIgnored() throws Exception {
        TcpSenderEngine sender = sender(100, 0, 10, 4);
        sender.queueData(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        sender.receiveAcknowledgment(ack(500, 100, 4));

        AckProcessingResult stale =
                sender.receiveAcknowledgment(ack(499, 100, 8));

        assertEquals(AckDisposition.DUPLICATE_ACK, stale.disposition());
        assertFalse(stale.windowChanged());
        assertEquals(4, sender.sendWindow());
        assertEquals(List.of(), stale.transmissions());
    }

    @Test
    void oldAckCannotUpdateWindowEvenWithNewerSegmentSequence() throws Exception {
        TcpSenderEngine sender = sender(100, 4, 4, 4);
        sender.queueData(new byte[] {1, 2, 3, 4});
        sender.receiveAcknowledgment(ack(500, 104, 4));

        AckProcessingResult old =
                sender.receiveAcknowledgment(ack(501, 102, 8));

        assertEquals(AckDisposition.OLD_ACK, old.disposition());
        assertFalse(old.windowChanged());
        assertEquals(4, sender.sendWindow());
    }

    @Test
    void windowShrinkDoesNotDiscardOutstandingData() throws Exception {
        TcpSenderEngine sender = sender(100, 8, 8, 4);
        sender.queueData(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});

        AckProcessingResult result =
                sender.receiveAcknowledgment(ack(500, 100, 2));

        assertTrue(result.windowChanged());
        assertEquals(2, sender.sendWindow());
        assertEquals(8, sender.flightSize());
        assertEquals(2, sender.outstandingSegments().size());
        assertEquals(List.of(), result.transmissions());
    }

    @Test
    void corruptedAckDoesNotChangeSenderState() throws Exception {
        TcpSenderEngine sender = sender(100, 4, 4, 4);
        sender.queueData(new byte[] {1, 2, 3, 4});
        TcpSegment validAck = ack(500, 104, 4);
        TcpSegment corruptedAck = validAck.withPayload(new byte[] {1});

        AckProcessingResult result = sender.receiveAcknowledgment(corruptedAck);

        assertEquals(AckDisposition.CHECKSUM_FAILED, result.disposition());
        assertEquals(100, sender.sendUnacknowledged().toLong());
        assertEquals(4, sender.flightSize());
    }

    @Test
    void segmentWithoutAckFlagIsIgnored() throws Exception {
        TcpSenderEngine sender = sender(100, 4, 4, 4);
        sender.queueData(new byte[] {1, 2, 3, 4});
        TcpSegment notAck = TcpChecksum.apply(new TcpSegment(
                ipv4("198.51.100.2"),
                ipv4("192.0.2.1"),
                19002,
                19001,
                500,
                104,
                Set.of(),
                4,
                0,
                new byte[0]));

        AckProcessingResult result = sender.receiveAcknowledgment(notAck);

        assertEquals(AckDisposition.NOT_AN_ACK, result.disposition());
        assertEquals(4, sender.flightSize());
    }

    @Test
    void acknowledgmentForDifferentConnectionIsIgnored() throws Exception {
        TcpSenderEngine sender = sender(100, 4, 4, 4);
        sender.queueData(new byte[] {1, 2, 3, 4});
        TcpSegment wrongConnection = TcpChecksum.apply(new TcpSegment(
                ipv4("203.0.113.9"),
                ipv4("192.0.2.1"),
                19002,
                19001,
                500,
                104,
                Set.of(TcpFlag.ACK),
                4,
                0,
                new byte[0]));

        AckProcessingResult result =
                sender.receiveAcknowledgment(wrongConnection);

        assertEquals(AckDisposition.WRONG_CONNECTION, result.disposition());
        assertEquals(100, sender.sendUnacknowledged().toLong());
        assertEquals(4, sender.flightSize());
    }

    @Test
    void cumulativeAckWorksAcrossSequenceWrap() throws Exception {
        TcpSenderEngine sender = sender(0xFFFF_FFFEL, 4, 4, 4);
        List<TcpSegment> sent = sender.queueData(new byte[] {1, 2, 3, 4});

        assertEquals(0xFFFF_FFFEL, sent.get(0).sequenceNumber());
        assertEquals(2, sender.sendNext().toLong());

        AckProcessingResult result =
                sender.receiveAcknowledgment(ack(500, 2, 4));

        assertEquals(AckDisposition.NEW_ACK, result.disposition());
        assertEquals(4, result.newlyAcknowledgedBytes());
        assertEquals(0, sender.flightSize());
        assertEquals(2, sender.sendUnacknowledged().toLong());
    }

    @Test
    void queuedApplicationDataIsDefensivelyCopied() throws Exception {
        TcpSenderEngine sender = sender(100, 0, 4, 4);
        byte[] data = {1, 2, 3, 4};
        sender.queueData(data);
        data[0] = 99;

        AckProcessingResult result =
                sender.receiveAcknowledgment(ack(500, 100, 4));

        assertArrayEquals(new byte[] {1, 2, 3, 4}, result.transmissions().get(0).payload());
    }

    @Test
    void congestionWindowIncreaseAllowsMoreQueuedData() throws Exception {
        TcpSenderEngine sender = sender(100, 10, 2, 4);
        sender.queueData(new byte[] {1, 2, 3, 4, 5, 6});

        List<TcpSegment> transmissions = sender.updateCongestionWindow(6);

        assertEquals(1, transmissions.size());
        assertSegment(transmissions.get(0), 102, new byte[] {3, 4, 5, 6});
        assertEquals(6, sender.flightSize());
    }

    private static TcpSenderEngine sender(
            long initialSequence, int receiverWindow, long congestionWindow, int smss)
            throws Exception {
        return new TcpSenderEngine(new SenderConfig(
                ipv4("192.0.2.1"),
                ipv4("198.51.100.2"),
                19001,
                19002,
                SequenceNumber32.of(initialSequence),
                SequenceNumber32.of(500),
                4096,
                receiverWindow,
                smss,
                congestionWindow));
    }

    private static TcpSegment ack(long sequence, long acknowledgment, int window)
            throws Exception {
        return TcpChecksum.apply(new TcpSegment(
                ipv4("198.51.100.2"),
                ipv4("192.0.2.1"),
                19002,
                19001,
                sequence,
                acknowledgment,
                Set.of(TcpFlag.ACK),
                window,
                0,
                new byte[0]));
    }

    private static void assertSegment(
            TcpSegment segment, long sequenceNumber, byte[] payload) {
        assertEquals(sequenceNumber, segment.sequenceNumber());
        assertArrayEquals(payload, segment.payload());
        assertTrue(segment.hasFlag(TcpFlag.ACK));
        assertTrue(TcpChecksum.isValid(segment));
    }

    private static Inet4Address ipv4(String address) throws Exception {
        return (Inet4Address) InetAddress.getByName(address);
    }
}
