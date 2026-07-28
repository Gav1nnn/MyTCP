package com.ouc.tcp.integration;

import com.ouc.tcp.checksum.TcpChecksum;
import com.ouc.tcp.congestion.CongestionPhase;
import com.ouc.tcp.core.AckProcessingResult;
import com.ouc.tcp.core.ReceiveResult;
import com.ouc.tcp.core.SenderConfig;
import com.ouc.tcp.core.SequenceNumber32;
import com.ouc.tcp.core.TcpFlag;
import com.ouc.tcp.core.TcpReceiverEngine;
import com.ouc.tcp.core.TcpSegment;
import com.ouc.tcp.core.TcpSenderEngine;
import com.ouc.tcp.simulator.DeterministicChannel;
import com.ouc.tcp.simulator.DeterministicScheduler;
import com.ouc.tcp.simulator.ManualClock;
import com.ouc.tcp.simulator.TransmissionBehaviors;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpEndToEndTest {
    @Test
    void transfersMultipleWindowsInOrder() throws Exception {
        Fixture fixture = new Fixture(1);
        byte[] input = bytes(80);

        fixture.send(input);
        fixture.scheduler.runReady();

        assertArrayEquals(input, fixture.delivered.toByteArray());
        assertEquals(0, fixture.sender.flightSize());
        assertEquals(0, fixture.sender.pendingByteCount());
    }

    @Test
    void firstSegmentLossIsRecoveredByFastRetransmit() throws Exception {
        Fixture fixture = new Fixture(1);
        byte[] input = bytes(40);
        fixture.forward.enqueue(TransmissionBehaviors.drop());

        fixture.send(input);
        fixture.scheduler.runReady();

        assertArrayEquals(input, fixture.delivered.toByteArray());
        assertTrue(transmissionCountAt(fixture, 1) >= 2);
        assertEquals(0, fixture.sender.flightSize());
        assertEquals(
                CongestionPhase.CONGESTION_AVOIDANCE,
                fixture.sender.congestionPhase());
    }

    @Test
    void corruptedSegmentIsDiscardedAndRecoveredWithoutBadDelivery()
            throws Exception {
        Fixture fixture = new Fixture(1);
        byte[] input = bytes(40);
        fixture.forward.enqueue(
                TransmissionBehaviors.corruptPayload(0, 0xFF, Duration.ZERO));

        fixture.send(input);
        fixture.scheduler.runReady();

        assertArrayEquals(input, fixture.delivered.toByteArray());
        assertTrue(transmissionCountAt(fixture, 1) >= 2);
    }

    @Test
    void delayedOriginalAfterFastRetransmitIsDeliveredOnlyOnce()
            throws Exception {
        Fixture fixture = new Fixture(1);
        byte[] input = bytes(40);
        fixture.forward.enqueue(
                TransmissionBehaviors.deliverAfter(Duration.ofMillis(100)));

        fixture.send(input);
        fixture.scheduler.runReady();
        assertArrayEquals(input, fixture.delivered.toByteArray());

        fixture.scheduler.advanceBy(Duration.ofMillis(100));
        assertArrayEquals(input, fixture.delivered.toByteArray());
    }

    @Test
    void lostAcknowledgmentsAreRecoveredByRetransmissionTimeout()
            throws Exception {
        Fixture fixture = new Fixture(1);
        byte[] input = bytes(16);
        for (int index = 0; index < 4; index++) {
            fixture.reverse.enqueue(TransmissionBehaviors.drop());
        }

        fixture.send(input);
        fixture.scheduler.runReady();
        assertArrayEquals(input, fixture.delivered.toByteArray());
        assertEquals(16, fixture.sender.flightSize());

        fixture.scheduler.advanceBy(Duration.ofSeconds(1));

        assertArrayEquals(input, fixture.delivered.toByteArray());
        assertEquals(0, fixture.sender.flightSize());
        assertEquals(Duration.ofSeconds(2), fixture.sender.retransmissionTimeout());
        assertEquals(8, fixture.sender.slowStartThreshold());
        assertEquals(8, fixture.sender.congestionWindow());
        assertEquals(CongestionPhase.CONGESTION_AVOIDANCE,
                fixture.sender.congestionPhase());
    }

    @Test
    void endToEndTransferWorksAcrossSequenceNumberWrap() throws Exception {
        long initialSequence = 0xFFFF_FFF9L;
        Fixture fixture = new Fixture(initialSequence);
        byte[] input = bytes(24);

        fixture.send(input);
        fixture.scheduler.runReady();

        assertArrayEquals(input, fixture.delivered.toByteArray());
        assertEquals(
                SequenceNumber32.of(initialSequence).add(input.length),
                fixture.sender.sendUnacknowledged());
        assertEquals(0, fixture.sender.flightSize());
    }

    private static long transmissionCountAt(Fixture fixture, long sequenceNumber) {
        return fixture.forward.transmissions().stream()
                .filter(segment -> segment.sequenceNumber() == sequenceNumber)
                .count();
    }

    private static byte[] bytes(int length) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) {
            bytes[index] = (byte) (index * 31 + 7);
        }
        return bytes;
    }

    private static Inet4Address ipv4(String address) throws Exception {
        return (Inet4Address) InetAddress.getByName(address);
    }

    private static final class Fixture {
        private static final int SENDER_PORT = 19001;
        private static final int RECEIVER_PORT = 19002;
        private static final int SMSS = 4;
        private static final int WINDOW = 64;

        private final Inet4Address senderAddress;
        private final Inet4Address receiverAddress;
        private final DeterministicScheduler scheduler;
        private final TcpReceiverEngine receiver;
        private final DeterministicChannel forward;
        private final DeterministicChannel reverse;
        private final TcpSenderEngine sender;
        private final ByteArrayOutputStream delivered = new ByteArrayOutputStream();

        private Fixture(long initialSequence) throws Exception {
            senderAddress = ipv4("192.0.2.1");
            receiverAddress = ipv4("198.51.100.2");
            ManualClock clock = new ManualClock();
            scheduler = new DeterministicScheduler(clock);
            receiver = new TcpReceiverEngine(
                    SequenceNumber32.of(initialSequence), WINDOW);
            forward = new DeterministicChannel(scheduler, this::receiveData);
            reverse = new DeterministicChannel(scheduler, this::receiveAck);
            sender = new TcpSenderEngine(
                    new SenderConfig(
                            senderAddress,
                            receiverAddress,
                            SENDER_PORT,
                            RECEIVER_PORT,
                            SequenceNumber32.of(initialSequence),
                            SequenceNumber32.of(500),
                            WINDOW,
                            WINDOW,
                            SMSS,
                            4L * SMSS,
                            16L * SMSS),
                    clock,
                    scheduler,
                    forward::send);
        }

        private void send(byte[] bytes) {
            sender.queueData(bytes).forEach(forward::send);
        }

        private void receiveData(TcpSegment segment) {
            ReceiveResult result = receiver.receive(segment);
            delivered.writeBytes(result.deliveredBytes());
            if (result.acknowledgmentRequired()) {
                reverse.send(acknowledgment(result));
            }
        }

        private void receiveAck(TcpSegment segment) {
            AckProcessingResult result = sender.receiveAcknowledgment(segment);
            result.transmissions().forEach(forward::send);
        }

        private TcpSegment acknowledgment(ReceiveResult result) {
            return TcpChecksum.apply(new TcpSegment(
                    receiverAddress,
                    senderAddress,
                    RECEIVER_PORT,
                    SENDER_PORT,
                    500,
                    result.acknowledgmentNumber().toLong(),
                    Set.of(TcpFlag.ACK),
                    result.advertisedWindow(),
                    0,
                    new byte[0]));
        }
    }
}
