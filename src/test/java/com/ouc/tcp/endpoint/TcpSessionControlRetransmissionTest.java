package com.ouc.tcp.endpoint;

import com.ouc.tcp.connection.ConnectionConfig;
import com.ouc.tcp.connection.ControlRetryPolicy;
import com.ouc.tcp.connection.TcpState;
import com.ouc.tcp.core.SequenceNumber32;
import com.ouc.tcp.core.TcpFlag;
import com.ouc.tcp.core.TcpSegment;
import com.ouc.tcp.transport.SegmentTransport;
import com.ouc.tcp.transport.UdpSegmentTransport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TcpSessionControlRetransmissionTest {
    @Test
    void retransmitsFinWhenTheFirstFinIsLost() throws Exception {
        Inet4Address loopback = ipv4("127.0.0.1");
        UdpSegmentTransport clientUdp =
                new UdpSegmentTransport(loopback, 0);
        UdpSegmentTransport serverUdp =
                new UdpSegmentTransport(loopback, 0);
        DropFirstFinTransport clientTransport =
                new DropFirstFinTransport(clientUdp);
        ConnectionConfig clientConfig = config(
                loopback,
                clientUdp.localPort(),
                serverUdp.localPort(),
                1_000);
        ConnectionConfig serverConfig = config(
                loopback,
                serverUdp.localPort(),
                clientUdp.localPort(),
                9_000);
        ControlRetryPolicy retry =
                new ControlRetryPolicy(Duration.ofMillis(20), 5);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<TcpSession> passive = executor.submit(() ->
                    TcpSession.openPassive(
                            serverConfig,
                            serverUdp,
                            retry,
                            EndpointTuning.defaults()));
            try (TcpSession client = TcpSession.openActive(
                            clientConfig,
                            clientTransport,
                            retry,
                            EndpointTuning.defaults());
                    TcpSession server = passive.get()) {
                client.initiateClose();
                server.poll(Duration.ofSeconds(1));

                assertEquals(2, clientTransport.finSendCount());
                assertEquals(TcpState.CLOSE_WAIT, server.state());

                client.poll(Duration.ofSeconds(1));
                server.initiateClose();
                client.poll(Duration.ofSeconds(1));
                server.poll(Duration.ofSeconds(1));
                client.expireTimeWait();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static ConnectionConfig config(
            Inet4Address address,
            int localPort,
            int remotePort,
            long initialSequence) {
        return new ConnectionConfig(
                address,
                address,
                localPort,
                remotePort,
                SequenceNumber32.of(initialSequence),
                32_768);
    }

    private static Inet4Address ipv4(String address) throws Exception {
        return (Inet4Address) InetAddress.getByName(address);
    }

    private static final class DropFirstFinTransport
            implements SegmentTransport {
        private final SegmentTransport delegate;
        private final AtomicInteger finSendCount = new AtomicInteger();

        private DropFirstFinTransport(SegmentTransport delegate) {
            this.delegate = delegate;
        }

        @Override
        public void send(TcpSegment segment) throws IOException {
            if (segment.hasFlag(TcpFlag.FIN)) {
                if (finSendCount.incrementAndGet() == 1) {
                    return;
                }
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

        private int finSendCount() {
            return finSendCount.get();
        }
    }
}
