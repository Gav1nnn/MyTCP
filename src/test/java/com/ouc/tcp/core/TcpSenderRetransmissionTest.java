package com.ouc.tcp.core;

import com.ouc.tcp.checksum.TcpChecksum;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpSenderRetransmissionTest {
    @Test
    void timeoutRetransmitsOnlyTheEarliestSegmentAndBacksOffRto()
            throws Exception {
        SenderFixture fixture = sender(100, 8, 8, 4);
        List<TcpSegment> original =
                fixture.sender.queueData(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});

        assertTrue(fixture.sender.retransmissionTimerRunning());
        fixture.scheduler.advanceBy(Duration.ofMillis(999));
        assertEquals(List.of(), fixture.retransmissions);

        fixture.scheduler.advanceBy(Duration.ofMillis(1));
        assertEquals(List.of(original.get(0)), fixture.retransmissions);
        assertEquals(Duration.ofSeconds(2), fixture.sender.retransmissionTimeout());
        assertEquals(100, fixture.sender.sendUnacknowledged().toLong());
        assertEquals(108, fixture.sender.sendNext().toLong());
        assertEquals(8, fixture.sender.flightSize());

        fixture.scheduler.advanceBy(Duration.ofSeconds(2));
        assertEquals(List.of(original.get(0), original.get(0)), fixture.retransmissions);
        assertEquals(Duration.ofSeconds(4), fixture.sender.retransmissionTimeout());
    }

    @Test
    void acknowledgingAllOutstandingDataStopsTimer() throws Exception {
        SenderFixture fixture = sender(100, 4, 4, 4);
        fixture.sender.queueData(new byte[] {1, 2, 3, 4});
        fixture.scheduler.advanceBy(Duration.ofMillis(100));

        fixture.sender.receiveAcknowledgment(ack(500, 104, 4));

        assertTrue(!fixture.sender.retransmissionTimerRunning());
        fixture.scheduler.advanceBy(Duration.ofSeconds(2));
        assertEquals(List.of(), fixture.retransmissions);
    }

    @Test
    void newAcknowledgmentRestartsTimerFromAckArrival() throws Exception {
        SenderFixture fixture = sender(100, 8, 8, 4);
        fixture.sender.queueData(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        fixture.scheduler.advanceBy(Duration.ofMillis(900));

        fixture.sender.receiveAcknowledgment(ack(500, 104, 8));
        assertEquals(
                Duration.ofMillis(2700),
                fixture.sender.retransmissionTimeout());
        fixture.scheduler.advanceBy(Duration.ofMillis(2699));
        assertEquals(List.of(), fixture.retransmissions);

        fixture.scheduler.advanceBy(Duration.ofMillis(1));
        assertEquals(104, fixture.retransmissions.get(0).sequenceNumber());
    }

    @Test
    void duplicateAcknowledgmentDoesNotRestartTimer() throws Exception {
        SenderFixture fixture = sender(100, 4, 4, 4);
        fixture.sender.queueData(new byte[] {1, 2, 3, 4});
        fixture.scheduler.advanceBy(Duration.ofMillis(900));

        fixture.sender.receiveAcknowledgment(ack(500, 100, 4));
        fixture.scheduler.advanceBy(Duration.ofMillis(100));

        assertEquals(1, fixture.retransmissions.size());
    }

    @Test
    void unambiguousAcknowledgmentProducesRttMeasurement() throws Exception {
        SenderFixture fixture = sender(100, 4, 4, 4);
        fixture.sender.queueData(new byte[] {1, 2, 3, 4});
        fixture.scheduler.advanceBy(Duration.ofMillis(250));

        fixture.sender.receiveAcknowledgment(ack(500, 104, 4));

        assertEquals(
                Duration.ofMillis(250),
                fixture.sender.smoothedRtt().orElseThrow());
        assertEquals(
                Duration.ofMillis(125),
                fixture.sender.rttVariation().orElseThrow());
        assertEquals(Duration.ofSeconds(1), fixture.sender.retransmissionTimeout());
    }

    @Test
    void karnAlgorithmRejectsMeasurementAfterRetransmission() throws Exception {
        SenderFixture fixture = sender(100, 4, 4, 4);
        fixture.sender.queueData(new byte[] {1, 2, 3, 4});
        fixture.scheduler.advanceBy(Duration.ofSeconds(1));
        fixture.scheduler.advanceBy(Duration.ofMillis(100));

        fixture.sender.receiveAcknowledgment(ack(500, 104, 4));

        assertTrue(fixture.sender.smoothedRtt().isEmpty());
        assertEquals(Duration.ofSeconds(2), fixture.sender.retransmissionTimeout());
    }

    private static SenderFixture sender(
            long initialSequence, int receiverWindow, long congestionWindow, int smss)
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
                        congestionWindow),
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

    private static Inet4Address ipv4(String address) throws Exception {
        return (Inet4Address) InetAddress.getByName(address);
    }

    private record SenderFixture(
            TcpSenderEngine sender,
            DeterministicScheduler scheduler,
            List<TcpSegment> retransmissions) {
    }
}
