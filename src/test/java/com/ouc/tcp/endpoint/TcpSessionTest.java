package com.ouc.tcp.endpoint;

import com.ouc.tcp.connection.ConnectionConfig;
import com.ouc.tcp.connection.ControlRetryPolicy;
import com.ouc.tcp.connection.SessionTiming;
import com.ouc.tcp.connection.TcpState;
import com.ouc.tcp.core.SequenceNumber32;
import com.ouc.tcp.transport.UdpSegmentTransport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpSessionTest {
    @Test
    void runsHandshakeDataTransferAndOrderlyClose() throws Exception {
        Inet4Address loopback = ipv4("127.0.0.1");
        UdpSegmentTransport clientTransport =
                new UdpSegmentTransport(loopback, 0);
        UdpSegmentTransport serverTransport =
                new UdpSegmentTransport(loopback, 0);
        ConnectionConfig clientConfig = config(
                loopback,
                clientTransport.localPort(),
                serverTransport.localPort(),
                1_000);
        ConnectionConfig serverConfig = config(
                loopback,
                serverTransport.localPort(),
                clientTransport.localPort(),
                9_000);
        ControlRetryPolicy retry =
                new ControlRetryPolicy(Duration.ofMillis(50), 10);
        EndpointTuning tuning = EndpointTuning.defaults();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<TcpSession> passive = executor.submit(() ->
                    TcpSession.openPassive(
                            serverConfig,
                            serverTransport,
                            retry,
                            new SessionTiming(Duration.ofMillis(5)),
                            tuning));
            try (TcpSession client = TcpSession.openActive(
                            clientConfig,
                            clientTransport,
                            retry,
                            new SessionTiming(Duration.ofMillis(5)),
                            tuning);
                    TcpSession server = passive.get()) {
                byte[] expected = new byte[25_123];
                for (int index = 0; index < expected.length; index++) {
                    expected[index] = (byte) (index * 17);
                }
                client.send(expected);

                ByteArrayOutputStream received = new ByteArrayOutputStream();
                while (!client.sendComplete()
                        || received.size() < expected.length) {
                    received.write(server
                            .poll(Duration.ofSeconds(1))
                            .deliveredBytes());
                    client.poll(Duration.ofSeconds(1));
                }
                assertArrayEquals(expected, received.toByteArray());
                assertTrue(client.congestionWindow()
                        >= tuning.initialCongestionWindow());

                client.initiateClose();
                assertEquals(TcpState.FIN_WAIT_1, client.state());
                server.poll(Duration.ofSeconds(1));
                assertEquals(TcpState.CLOSE_WAIT, server.state());
                client.poll(Duration.ofSeconds(1));
                assertEquals(TcpState.FIN_WAIT_2, client.state());

                server.initiateClose();
                assertEquals(TcpState.LAST_ACK, server.state());
                client.poll(Duration.ofSeconds(1));
                assertEquals(TcpState.TIME_WAIT, client.state());
                server.poll(Duration.ofSeconds(1));
                assertEquals(TcpState.CLOSED, server.state());
                while (client.state() != TcpState.CLOSED) {
                    try {
                        client.poll(Duration.ofMillis(20));
                    } catch (java.net.SocketTimeoutException timeout) {
                        // The TIME-WAIT timer advances independently.
                    }
                }
                assertEquals(TcpState.CLOSED, client.state());
            }
        } finally {
            executor.shutdownNow();
        }
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
}
