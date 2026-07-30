package com.ouc.tcp.endpoint;

import com.ouc.tcp.checksum.TcpChecksum;
import com.ouc.tcp.core.SequenceNumber32;
import com.ouc.tcp.core.TcpFlag;
import com.ouc.tcp.core.TcpSegment;
import com.ouc.tcp.transport.SegmentTransport;
import com.ouc.tcp.transport.UdpSegmentTransport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandaloneTcpEndpointTest {
    @Test
    void transfersOrderedBytesOverIndependentUdpTransports() throws Exception {
        Inet4Address loopback = ipv4("127.0.0.1");
        UdpSegmentTransport leftTransport = new UdpSegmentTransport(loopback, 0);
        UdpSegmentTransport rightTransport = new UdpSegmentTransport(loopback, 0);
        EndpointConfig leftConfig = config(
                loopback,
                leftTransport.localPort(),
                rightTransport.localPort(),
                1_001,
                9_001);
        EndpointConfig rightConfig = config(
                loopback,
                rightTransport.localPort(),
                leftTransport.localPort(),
                9_001,
                1_001);

        try (StandaloneTcpEndpoint left =
                        new StandaloneTcpEndpoint(leftConfig, leftTransport);
                StandaloneTcpEndpoint right =
                        new StandaloneTcpEndpoint(rightConfig, rightTransport)) {
            byte[] expected = new byte[48_731];
            for (int index = 0; index < expected.length; index++) {
                expected[index] = (byte) (index * 31);
            }
            left.send(expected);

            ByteArrayOutputStream delivered = new ByteArrayOutputStream();
            int iterations = 0;
            while ((!left.sendComplete() || delivered.size() < expected.length)
                    && iterations++ < 1_000) {
                EndpointPollResult data = right.poll(Duration.ofSeconds(1));
                delivered.write(data.deliveredBytes());
                left.poll(Duration.ofSeconds(1));
            }

            assertTrue(left.sendComplete());
            assertArrayEquals(expected, delivered.toByteArray());
            assertTrue(left.congestionWindow() >= leftConfig.initialCongestionWindow());
        }
    }

    @Test
    void supportsBidirectionalApplicationData() throws Exception {
        Inet4Address loopback = ipv4("127.0.0.1");
        UdpSegmentTransport leftTransport = new UdpSegmentTransport(loopback, 0);
        UdpSegmentTransport rightTransport = new UdpSegmentTransport(loopback, 0);
        EndpointConfig leftConfig = config(
                loopback,
                leftTransport.localPort(),
                rightTransport.localPort(),
                10,
                20);
        EndpointConfig rightConfig = config(
                loopback,
                rightTransport.localPort(),
                leftTransport.localPort(),
                20,
                10);

        try (StandaloneTcpEndpoint left =
                        new StandaloneTcpEndpoint(leftConfig, leftTransport);
                StandaloneTcpEndpoint right =
                        new StandaloneTcpEndpoint(rightConfig, rightTransport)) {
            byte[] fromLeft = new byte[2_345];
            byte[] fromRight = new byte[1_234];
            Arrays.fill(fromLeft, (byte) 0x5A);
            Arrays.fill(fromRight, (byte) 0x33);
            left.send(fromLeft);
            right.send(fromRight);

            ByteArrayOutputStream atLeft = new ByteArrayOutputStream();
            ByteArrayOutputStream atRight = new ByteArrayOutputStream();
            for (int iteration = 0;
                    iteration < 200
                            && (!left.sendComplete()
                                    || !right.sendComplete()
                                    || atLeft.size() < fromRight.length
                                    || atRight.size() < fromLeft.length);
                    iteration++) {
                atRight.write(right.poll(Duration.ofSeconds(1)).deliveredBytes());
                atLeft.write(left.poll(Duration.ofSeconds(1)).deliveredBytes());
            }

            assertArrayEquals(fromRight, atLeft.toByteArray());
            assertArrayEquals(fromLeft, atRight.toByteArray());
            assertTrue(left.sendComplete());
            assertTrue(right.sendComplete());
        }
    }

    @Test
    void usesConfiguredInitialRetransmissionTimeout() throws Exception {
        Inet4Address loopback = ipv4("127.0.0.1");
        UdpSegmentTransport transport =
                new UdpSegmentTransport(loopback, 0);
        EndpointConfig config = new EndpointConfig(
                loopback,
                loopback,
                transport.localPort(),
                19_002,
                SequenceNumber32.of(1_001),
                SequenceNumber32.of(9_001),
                32_768,
                32_768,
                1_200,
                1_200,
                65_535,
                Duration.ofSeconds(3));

        try (StandaloneTcpEndpoint endpoint =
                new StandaloneTcpEndpoint(config, transport)) {
            assertEquals(
                    Duration.ofSeconds(3),
                    endpoint.retransmissionTimeout());
        }
    }

    @Test
    void rejectsOutOfWindowPureAckBeforeItCanReleaseData()
            throws Exception {
        Inet4Address loopback = ipv4("127.0.0.1");
        RecordingTransport transport = new RecordingTransport();
        EndpointConfig config = new EndpointConfig(
                loopback,
                loopback,
                19_001,
                19_002,
                SequenceNumber32.of(100),
                SequenceNumber32.of(500),
                32_768,
                32_768,
                1_200,
                3_600,
                65_535);

        try (StandaloneTcpEndpoint endpoint =
                new StandaloneTcpEndpoint(config, transport)) {
            endpoint.send(new byte[] {1, 2, 3, 4});
            TcpSegment staleAck = TcpChecksum.apply(new TcpSegment(
                    loopback,
                    loopback,
                    19_002,
                    19_001,
                    499,
                    104,
                    Set.of(TcpFlag.ACK),
                    32_768,
                    0,
                    new byte[0]));

            endpoint.process(staleAck);

            assertFalse(endpoint.sendComplete());
            assertEquals(2, transport.sent().size());
            TcpSegment challengeAck = transport.sent().get(1);
            assertEquals(104, challengeAck.sequenceNumber());
            assertEquals(500, challengeAck.acknowledgmentNumber());
            assertEquals(Set.of(TcpFlag.ACK), challengeAck.flags());
        }
    }

    private static EndpointConfig config(
            Inet4Address address,
            int localPort,
            int remotePort,
            long sendNext,
            long receiveNext) {
        return new EndpointConfig(
                address,
                address,
                localPort,
                remotePort,
                SequenceNumber32.of(sendNext),
                SequenceNumber32.of(receiveNext),
                32_768,
                32_768,
                1_200,
                4_800,
                65_535);
    }

    private static Inet4Address ipv4(String address) throws Exception {
        return (Inet4Address) InetAddress.getByName(address);
    }

    private static final class RecordingTransport
            implements SegmentTransport {
        private final List<TcpSegment> sent = new ArrayList<>();

        @Override
        public void send(TcpSegment segment) {
            sent.add(segment);
        }

        @Override
        public TcpSegment receive(Duration timeout) throws IOException {
            throw new IOException("receive is not used by this test");
        }

        @Override
        public void close() {
        }

        private List<TcpSegment> sent() {
            return List.copyOf(sent);
        }
    }
}
