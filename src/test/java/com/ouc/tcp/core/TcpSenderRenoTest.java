package com.ouc.tcp.core;

import com.ouc.tcp.checksum.TcpChecksum;
import com.ouc.tcp.congestion.CongestionPhase;
import com.ouc.tcp.simulator.DeterministicScheduler;
import com.ouc.tcp.simulator.ManualClock;
import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TcpSenderRenoTest {
    @Test
    void thirdDuplicateAckFastRetransmitsEarliestOutstandingSegment()
            throws Exception {
        SenderFixture fixture = sender(100, 100, 16, 64, 4);
        fixture.sender.queueData(bytes(40));

        fixture.sender.receiveAcknowledgment(ack(500, 100, 100));
        fixture.sender.receiveAcknowledgment(ack(500, 100, 100));
        AckProcessingResult third =
                fixture.sender.receiveAcknowledgment(ack(500, 100, 100));

        assertEquals(AckDisposition.DUPLICATE_ACK, third.disposition());
        assertEquals(1, third.transmissions().size());
        assertEquals(100, third.transmissions().get(0).sequenceNumber());
        assertEquals(8, fixture.sender.slowStartThreshold());
        assertEquals(20, fixture.sender.congestionWindow());
        assertEquals(3, fixture.sender.duplicateAckCount());
        assertEquals(
                CongestionPhase.FAST_RECOVERY,
                fixture.sender.congestionPhase());
    }

    @Test
    void firstTwoDuplicateAcksUseLimitedTransmitWithoutInflatingCwnd()
            throws Exception {
        SenderFixture fixture = sender(100, 100, 16, 64, 4);
        fixture.sender.queueData(bytes(40));

        AckProcessingResult first =
                fixture.sender.receiveAcknowledgment(ack(500, 100, 100));
        AckProcessingResult second =
                fixture.sender.receiveAcknowledgment(ack(500, 100, 100));

        assertEquals(116, first.transmissions().get(0).sequenceNumber());
        assertEquals(120, second.transmissions().get(0).sequenceNumber());
        assertEquals(16, fixture.sender.congestionWindow());
        assertEquals(24, fixture.sender.flightSize());
    }

    @Test
    void additionalDuplicateAckInflatesCwndAndEventuallyClocksNewData()
            throws Exception {
        SenderFixture fixture = sender(100, 100, 16, 64, 4);
        fixture.sender.queueData(bytes(40));
        fixture.sender.receiveAcknowledgment(ack(500, 100, 100));
        fixture.sender.receiveAcknowledgment(ack(500, 100, 100));
        fixture.sender.receiveAcknowledgment(ack(500, 100, 100));

        AckProcessingResult fourth =
                fixture.sender.receiveAcknowledgment(ack(500, 100, 100));
        AckProcessingResult fifth =
                fixture.sender.receiveAcknowledgment(ack(500, 100, 100));

        assertEquals(List.of(), fourth.transmissions());
        assertEquals(1, fifth.transmissions().size());
        assertEquals(124, fifth.transmissions().get(0).sequenceNumber());
        assertEquals(28, fixture.sender.congestionWindow());
    }

    @Test
    void newAckLeavesFastRecoveryAndDeflatesCwndToThreshold()
            throws Exception {
        SenderFixture fixture = sender(100, 100, 16, 64, 4);
        fixture.sender.queueData(bytes(40));
        fixture.sender.receiveAcknowledgment(ack(500, 100, 100));
        fixture.sender.receiveAcknowledgment(ack(500, 100, 100));
        fixture.sender.receiveAcknowledgment(ack(500, 100, 100));
        fixture.sender.receiveAcknowledgment(ack(500, 100, 100));

        fixture.sender.receiveAcknowledgment(ack(501, 120, 100));

        assertEquals(8, fixture.sender.congestionWindow());
        assertEquals(0, fixture.sender.duplicateAckCount());
        assertEquals(
                CongestionPhase.CONGESTION_AVOIDANCE,
                fixture.sender.congestionPhase());
    }

    @Test
    void retransmissionTimeoutReducesCwndAndThreshold() throws Exception {
        SenderFixture fixture = sender(100, 100, 16, 64, 4);
        fixture.sender.queueData(bytes(16));

        fixture.scheduler.advanceBy(Duration.ofSeconds(1));

        assertEquals(1, fixture.retransmissions.size());
        assertEquals(100, fixture.retransmissions.get(0).sequenceNumber());
        assertEquals(8, fixture.sender.slowStartThreshold());
        assertEquals(4, fixture.sender.congestionWindow());
        assertEquals(CongestionPhase.SLOW_START, fixture.sender.congestionPhase());
        assertEquals(16, fixture.sender.flightSize());
    }

    @Test
    void repeatedTimeoutDoesNotReduceThresholdAgain() throws Exception {
        SenderFixture fixture = sender(100, 100, 16, 64, 4);
        fixture.sender.queueData(bytes(16));

        fixture.scheduler.advanceBy(Duration.ofSeconds(1));
        fixture.scheduler.advanceBy(Duration.ofSeconds(2));

        assertEquals(2, fixture.retransmissions.size());
        assertEquals(8, fixture.sender.slowStartThreshold());
        assertEquals(4, fixture.sender.congestionWindow());
    }

    @Test
    void windowUpdateAckDoesNotCountTowardFastRetransmit() throws Exception {
        SenderFixture fixture = sender(100, 4, 4, 64, 4);
        fixture.sender.queueData(bytes(4));

        fixture.sender.receiveAcknowledgment(ack(500, 100, 5));
        fixture.sender.receiveAcknowledgment(ack(501, 100, 6));
        AckProcessingResult update =
                fixture.sender.receiveAcknowledgment(ack(502, 100, 7));

        assertEquals(0, fixture.sender.duplicateAckCount());
        assertEquals(List.of(), update.transmissions());
        assertEquals(4, fixture.sender.congestionWindow());
    }

    private static SenderFixture sender(
            long initialSequence,
            int receiverWindow,
            long congestionWindow,
            long slowStartThreshold,
            int smss)
            throws Exception {
        ManualClock clock = new ManualClock();
        DeterministicScheduler scheduler = new DeterministicScheduler(clock);
        List<TcpSegment> retransmissions = new ArrayList<>();
        TcpSenderEngine sender = new TcpSenderEngine(
                new SenderConfig(
                        ipv4("192.0.2.1"),
                        ipv4("198.51.100.2"),
                        19001,
                        19002,
                        SequenceNumber32.of(initialSequence),
                        SequenceNumber32.of(500),
                        4096,
                        receiverWindow,
                        smss,
                        congestionWindow,
                        slowStartThreshold),
                clock,
                scheduler,
                retransmissions::add);
        return new SenderFixture(sender, scheduler, retransmissions);
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

    private static byte[] bytes(int length) {
        byte[] result = new byte[length];
        for (int index = 0; index < length; index++) {
            result[index] = (byte) index;
        }
        return result;
    }

    private static Inet4Address ipv4(String address) throws Exception {
        return (Inet4Address) InetAddress.getByName(address);
    }

    private record SenderFixture(
            TcpSenderEngine sender,
            DeterministicScheduler scheduler,
            List<TcpSegment> retransmissions) {
    }
}
