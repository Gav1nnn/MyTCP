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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpSenderPersistTest {
    @Test
    void zeroWindowStartsPersistTimerWithoutSendingNormalData()
            throws Exception {
        SenderFixture fixture = sender(0, 4, 64);

        assertEquals(
                List.of(),
                fixture.sender.queueData(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}));

        assertTrue(fixture.sender.persistTimerRunning());
        assertEquals(
                Duration.ofSeconds(1),
                fixture.sender.persistInterval().orElseThrow());
        assertFalse(fixture.sender.retransmissionTimerRunning());
    }

    @Test
    void persistProbeDoesNotConsumeDataOrAdvanceSequenceSpace()
            throws Exception {
        SenderFixture fixture = sender(0, 4, 64);
        fixture.sender.queueData(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});

        fixture.scheduler.advanceBy(Duration.ofSeconds(1));

        assertEquals(1, fixture.transmissions.size());
        TcpSegment probe = fixture.transmissions.get(0);
        assertEquals(100, probe.sequenceNumber());
        assertArrayEquals(new byte[] {1, 2, 3, 4}, probe.payload());
        assertTrue(TcpChecksum.isValid(probe));
        assertEquals(100, fixture.sender.sendNext().toLong());
        assertEquals(0, fixture.sender.flightSize());
        assertEquals(8, fixture.sender.pendingByteCount());
        assertEquals(
                Duration.ofSeconds(2),
                fixture.sender.persistInterval().orElseThrow());
    }

    @Test
    void persistProbeIntervalBacksOffExponentially() throws Exception {
        SenderFixture fixture = sender(0, 4, 64);
        fixture.sender.queueData(new byte[] {1, 2, 3, 4});

        fixture.scheduler.advanceBy(Duration.ofSeconds(1));
        fixture.scheduler.advanceBy(Duration.ofMillis(1999));
        assertEquals(1, fixture.transmissions.size());

        fixture.scheduler.advanceBy(Duration.ofMillis(1));
        assertEquals(2, fixture.transmissions.size());
        assertEquals(
                Duration.ofSeconds(4),
                fixture.sender.persistInterval().orElseThrow());
    }

    @Test
    void windowReopeningStopsPersistAndSendsQueuedData() throws Exception {
        SenderFixture fixture = sender(0, 4, 64);
        fixture.sender.queueData(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        fixture.scheduler.advanceBy(Duration.ofSeconds(1));

        AckProcessingResult result =
                fixture.sender.receiveAcknowledgment(ack(500, 100, 4));

        assertFalse(fixture.sender.persistTimerRunning());
        assertTrue(fixture.sender.persistInterval().isEmpty());
        assertEquals(1, result.transmissions().size());
        assertEquals(100, result.transmissions().get(0).sequenceNumber());
        assertEquals(104, fixture.sender.sendNext().toLong());
        assertEquals(4, fixture.sender.flightSize());
        assertEquals(4, fixture.sender.pendingByteCount());
    }

    @Test
    void zeroWindowWithOutstandingDataUsesPersistInsteadOfRto()
            throws Exception {
        SenderFixture fixture = sender(4, 4, 64);
        fixture.sender.queueData(new byte[] {1, 2, 3, 4});

        fixture.sender.receiveAcknowledgment(ack(500, 100, 0));

        assertTrue(fixture.sender.persistTimerRunning());
        assertFalse(fixture.sender.retransmissionTimerRunning());
        assertEquals(4, fixture.sender.congestionWindow());

        fixture.scheduler.advanceBy(Duration.ofSeconds(1));
        assertEquals(1, fixture.transmissions.size());
        assertEquals(100, fixture.transmissions.get(0).sequenceNumber());
        assertEquals(4, fixture.sender.congestionWindow());
        assertEquals(4, fixture.sender.flightSize());
    }

    @Test
    void reopeningWindowRetransmitsOutstandingProbeAndRestoresRto()
            throws Exception {
        SenderFixture fixture = sender(4, 4, 64);
        fixture.sender.queueData(new byte[] {1, 2, 3, 4});
        fixture.sender.receiveAcknowledgment(ack(500, 100, 0));
        fixture.scheduler.advanceBy(Duration.ofSeconds(1));

        AckProcessingResult reopened =
                fixture.sender.receiveAcknowledgment(ack(501, 100, 4));

        assertEquals(1, reopened.transmissions().size());
        assertEquals(100, reopened.transmissions().get(0).sequenceNumber());
        assertFalse(fixture.sender.persistTimerRunning());
        assertTrue(fixture.sender.retransmissionTimerRunning());
        assertEquals(4, fixture.sender.flightSize());
    }

    @Test
    void longIdlePeriodRestartsAtInitialWindow() throws Exception {
        SenderFixture fixture = sender(64, 4, 64);
        fixture.sender.queueData(new byte[] {1, 2, 3, 4});
        fixture.scheduler.advanceBy(Duration.ofMillis(100));
        fixture.sender.receiveAcknowledgment(ack(500, 104, 64));
        assertEquals(8, fixture.sender.congestionWindow());

        fixture.scheduler.advanceBy(Duration.ofMillis(1001));
        List<TcpSegment> transmissions =
                fixture.sender.queueData(new byte[] {5, 6, 7, 8, 9, 10, 11, 12});

        assertEquals(4, fixture.sender.congestionWindow());
        assertEquals(CongestionPhase.SLOW_START, fixture.sender.congestionPhase());
        assertEquals(1, transmissions.size());
        assertArrayEquals(new byte[] {5, 6, 7, 8}, transmissions.get(0).payload());
        assertEquals(4, fixture.sender.pendingByteCount());
    }

    private static SenderFixture sender(
            int receiverWindow, long congestionWindow, long slowStartThreshold)
            throws Exception {
        ManualClock clock = new ManualClock();
        DeterministicScheduler scheduler = new DeterministicScheduler(clock);
        List<TcpSegment> transmissions = new ArrayList<>();
        TcpSenderEngine sender = new TcpSenderEngine(
                new SenderConfig(
                        ipv4("192.0.2.1"),
                        ipv4("198.51.100.2"),
                        19001,
                        19002,
                        SequenceNumber32.of(100),
                        SequenceNumber32.of(500),
                        64,
                        receiverWindow,
                        4,
                        congestionWindow,
                        slowStartThreshold),
                clock,
                scheduler,
                transmissions::add);
        return new SenderFixture(sender, scheduler, transmissions);
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
            List<TcpSegment> transmissions) {
    }
}
