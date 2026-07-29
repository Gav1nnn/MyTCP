package com.ouc.tcp.transport;

import com.ouc.tcp.checksum.TcpChecksum;
import com.ouc.tcp.core.TcpFlag;
import com.ouc.tcp.core.TcpSegment;
import com.ouc.tcp.wire.TcpWireCodec;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UdpSegmentTransportTest {
    @Test
    void exchangesChecksummedSegmentsOverLoopback() throws Exception {
        Inet4Address loopback = ipv4("127.0.0.1");
        try (UdpSegmentTransport left = new UdpSegmentTransport(loopback, 0);
                UdpSegmentTransport right = new UdpSegmentTransport(loopback, 0)) {
            TcpSegment sent = TcpChecksum.apply(segment(
                    loopback,
                    left.localPort(),
                    loopback,
                    right.localPort(),
                    new byte[] {1, 2, 3, 4}));

            left.send(sent);
            TcpSegment received = right.receive(Duration.ofSeconds(1));

            assertEquals(sent, received);
            assertTrue(TcpChecksum.isValid(received));
        }
    }

    @Test
    void rejectsSegmentWhoseSourceDoesNotMatchTransport() throws Exception {
        Inet4Address loopback = ipv4("127.0.0.1");
        try (UdpSegmentTransport transport = new UdpSegmentTransport(loopback, 0)) {
            TcpSegment wrongAddress = segment(
                    ipv4("127.0.0.2"),
                    transport.localPort(),
                    loopback,
                    9,
                    new byte[0]);
            TcpSegment wrongPort = segment(
                    loopback,
                    differentPort(transport.localPort()),
                    loopback,
                    9,
                    new byte[0]);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> transport.send(wrongAddress));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> transport.send(wrongPort));
        }
    }

    @Test
    void rejectsForgedTcpPortInsideUdpDatagram() throws Exception {
        Inet4Address loopback = ipv4("127.0.0.1");
        TcpWireCodec codec = new TcpWireCodec();
        try (UdpSegmentTransport receiver = new UdpSegmentTransport(loopback, 0);
                DatagramSocket rawSender = new DatagramSocket(0, loopback)) {
            TcpSegment forged = segment(
                    loopback,
                    differentPort(rawSender.getLocalPort()),
                    loopback,
                    receiver.localPort(),
                    new byte[] {1});
            byte[] encoded = codec.encode(forged);
            rawSender.send(new DatagramPacket(
                    encoded,
                    encoded.length,
                    loopback,
                    receiver.localPort()));

            assertThrows(
                    IllegalArgumentException.class,
                    () -> receiver.receive(Duration.ofSeconds(1)));
        }
    }

    @Test
    void receiveUsesBoundedBlocking() throws Exception {
        Inet4Address loopback = ipv4("127.0.0.1");
        try (UdpSegmentTransport receiver = new UdpSegmentTransport(loopback, 0)) {
            assertThrows(
                    SocketTimeoutException.class,
                    () -> receiver.receive(Duration.ofMillis(20)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> receiver.receive(Duration.ZERO));
        }
    }

    private static TcpSegment segment(
            Inet4Address sourceAddress,
            int sourcePort,
            Inet4Address destinationAddress,
            int destinationPort,
            byte[] payload) {
        return new TcpSegment(
                sourceAddress,
                destinationAddress,
                sourcePort,
                destinationPort,
                1,
                1,
                Set.of(TcpFlag.ACK),
                4096,
                0,
                payload);
    }

    private static int differentPort(int port) {
        return port == TcpSegment.MAX_PORT ? port - 1 : port + 1;
    }

    private static Inet4Address ipv4(String address) throws Exception {
        return (Inet4Address) InetAddress.getByName(address);
    }
}
