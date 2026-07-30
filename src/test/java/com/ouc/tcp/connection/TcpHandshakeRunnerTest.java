package com.ouc.tcp.connection;

import com.ouc.tcp.core.SequenceNumber32;
import com.ouc.tcp.core.TcpSegment;
import com.ouc.tcp.transport.SegmentTransport;
import com.ouc.tcp.transport.UdpSegmentTransport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpHandshakeRunnerTest {
    @Test
    void establishesActiveAndPassivePeersOverUdp() throws Exception {
        try (Fixture fixture = fixture(false)) {
            Handshakes handshakes = runHandshake(fixture);

            assertEquals(1_001, handshakes.client.sendNext().toLong());
            assertEquals(9_001, handshakes.client.receiveNext().toLong());
            assertEquals(9_001, handshakes.server.sendNext().toLong());
            assertEquals(1_001, handshakes.server.receiveNext().toLong());
            assertEquals(32_768, handshakes.client.peerAdvertisedWindow());
            assertEquals(32_768, handshakes.server.peerAdvertisedWindow());
            assertFalse(handshakes.client.localControlRetransmitted());
            assertFalse(handshakes.server.localControlRetransmitted());
            assertEquals(
                    Duration.ofSeconds(1),
                    handshakes.client.initialDataRetransmissionTimeout());
        }
    }

    @Test
    void retransmitsSynAfterFirstOutboundControlIsDropped() throws Exception {
        try (Fixture fixture = fixture(true)) {
            Handshakes handshakes = runHandshake(fixture);

            assertEquals(1_001, handshakes.client.sendNext().toLong());
            assertEquals(1_001, handshakes.server.receiveNext().toLong());
            assertTrue(handshakes.client.localControlRetransmitted());
            assertFalse(handshakes.server.localControlRetransmitted());
            assertEquals(
                    Duration.ofSeconds(3),
                    handshakes.client.initialDataRetransmissionTimeout());
        }
    }

    @Test
    void activeOpenFailsAfterConfiguredTimeoutLimit() throws Exception {
        Inet4Address loopback = ipv4("127.0.0.1");
        try (UdpSegmentTransport clientTransport =
                        new UdpSegmentTransport(loopback, 0);
                UdpSegmentTransport unusedPeer =
                        new UdpSegmentTransport(loopback, 0)) {
            ConnectionConfig clientConfig = config(
                    loopback,
                    clientTransport.localPort(),
                    unusedPeer.localPort(),
                    1_000);
            TcpHandshakeRunner client = new TcpHandshakeRunner(
                    new TcpConnectionLifecycle(clientConfig),
                    clientTransport,
                    new ControlRetryPolicy(Duration.ofMillis(10), 3));

            assertThrows(SocketTimeoutException.class, client::activeOpen);
        }
    }

    @Test
    void handshakeRetransmissionTimeoutBacksOffExponentially()
            throws Exception {
        Inet4Address loopback = ipv4("127.0.0.1");
        TimeoutTransport transport = new TimeoutTransport();
        TcpHandshakeRunner client = new TcpHandshakeRunner(
                new TcpConnectionLifecycle(new ConnectionConfig(
                        loopback,
                        loopback,
                        19_001,
                        19_002,
                        SequenceNumber32.of(1_000),
                        32_768)),
                transport,
                new ControlRetryPolicy(Duration.ofMillis(10), 3));

        assertThrows(SocketTimeoutException.class, client::activeOpen);
        assertEquals(
                List.of(
                        Duration.ofMillis(10),
                        Duration.ofMillis(20),
                        Duration.ofMillis(40)),
                transport.receiveTimeouts());
    }

    private static Handshakes runHandshake(Fixture fixture) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<HandshakeResult> passive =
                    executor.submit(fixture.serverRunner::passiveOpen);
            HandshakeResult active = fixture.clientRunner.activeOpen();
            return new Handshakes(active, passive.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private static Fixture fixture(boolean dropFirstClientSend) throws Exception {
        Inet4Address loopback = ipv4("127.0.0.1");
        UdpSegmentTransport clientUdp = new UdpSegmentTransport(loopback, 0);
        UdpSegmentTransport serverUdp = new UdpSegmentTransport(loopback, 0);
        SegmentTransport clientTransport = dropFirstClientSend
                ? new DropFirstSendTransport(clientUdp)
                : clientUdp;
        ConnectionConfig clientConfig = config(
                loopback, clientUdp.localPort(), serverUdp.localPort(), 1_000);
        ConnectionConfig serverConfig = config(
                loopback, serverUdp.localPort(), clientUdp.localPort(), 9_000);
        ControlRetryPolicy policy =
                new ControlRetryPolicy(Duration.ofMillis(20), 10);
        return new Fixture(
                clientTransport,
                serverUdp,
                new TcpHandshakeRunner(
                        new TcpConnectionLifecycle(clientConfig),
                        clientTransport,
                        policy),
                new TcpHandshakeRunner(
                        new TcpConnectionLifecycle(serverConfig),
                        serverUdp,
                        policy));
    }

    private static ConnectionConfig config(
            Inet4Address address, int localPort, int remotePort, long isn) {
        return new ConnectionConfig(
                address,
                address,
                localPort,
                remotePort,
                SequenceNumber32.of(isn),
                32_768);
    }

    private static Inet4Address ipv4(String address) throws Exception {
        return (Inet4Address) InetAddress.getByName(address);
    }

    private record Handshakes(HandshakeResult client, HandshakeResult server) {
    }

    private record Fixture(
            SegmentTransport clientTransport,
            SegmentTransport serverTransport,
            TcpHandshakeRunner clientRunner,
            TcpHandshakeRunner serverRunner) implements AutoCloseable {
        @Override
        public void close() {
            clientTransport.close();
            serverTransport.close();
        }
    }

    private static final class DropFirstSendTransport
            implements SegmentTransport {
        private final SegmentTransport delegate;
        private boolean dropped;

        private DropFirstSendTransport(SegmentTransport delegate) {
            this.delegate = delegate;
        }

        @Override
        public void send(TcpSegment segment) throws IOException {
            if (!dropped) {
                dropped = true;
                return;
            }
            delegate.send(segment);
        }

        @Override
        public TcpSegment receive(Duration timeout) throws IOException {
            return delegate.receive(timeout);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static final class TimeoutTransport implements SegmentTransport {
        private final List<Duration> receiveTimeouts = new ArrayList<>();

        @Override
        public void send(TcpSegment segment) {
        }

        @Override
        public TcpSegment receive(Duration timeout)
                throws SocketTimeoutException {
            receiveTimeouts.add(timeout);
            throw new SocketTimeoutException("injected timeout");
        }

        @Override
        public void close() {
        }

        private List<Duration> receiveTimeouts() {
            return List.copyOf(receiveTimeouts);
        }
    }
}
