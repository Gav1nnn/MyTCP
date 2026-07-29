package com.ouc.tcp.transport;

import com.ouc.tcp.core.TcpSegment;
import com.ouc.tcp.wire.TcpWireCodec;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.time.Duration;
import java.util.Objects;

/**
 * Carries standalone TCP wire segments inside local UDP datagrams.
 *
 * <p>The UDP address and port are required to match the logical TCP
 * four-tuple. This keeps the tunnel boundary explicit and prevents a caller
 * from silently sending a segment under a different source identity.</p>
 */
public final class UdpSegmentTransport implements SegmentTransport {
    public static final int MAX_UDP_PAYLOAD_LENGTH = 65_507;

    private final Inet4Address localAddress;
    private final DatagramSocket socket;
    private final TcpWireCodec codec;

    public UdpSegmentTransport(Inet4Address localAddress, int localPort)
            throws SocketException {
        this(localAddress, localPort, new TcpWireCodec());
    }

    UdpSegmentTransport(
            Inet4Address localAddress,
            int localPort,
            TcpWireCodec codec) throws SocketException {
        this.localAddress = Objects.requireNonNull(localAddress, "localAddress");
        if (localPort < 0 || localPort > TcpSegment.MAX_PORT) {
            throw new IllegalArgumentException(
                    "localPort must be an unsigned 16-bit value");
        }
        this.codec = Objects.requireNonNull(codec, "codec");
        socket = new DatagramSocket(new InetSocketAddress(localAddress, localPort));
    }

    public Inet4Address localAddress() {
        return localAddress;
    }

    public int localPort() {
        return socket.getLocalPort();
    }

    @Override
    public void send(TcpSegment segment) throws IOException {
        Objects.requireNonNull(segment, "segment");
        if (!segment.sourceAddress().equals(localAddress)) {
            throw new IllegalArgumentException(
                    "segment source address does not match UDP transport");
        }
        if (segment.sourcePort() != localPort()) {
            throw new IllegalArgumentException(
                    "segment source port does not match UDP transport");
        }

        byte[] bytes = codec.encode(segment);
        if (bytes.length > MAX_UDP_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException(
                    "encoded TCP segment exceeds the UDP payload limit");
        }
        DatagramPacket packet = new DatagramPacket(
                bytes,
                bytes.length,
                segment.destinationAddress(),
                segment.destinationPort());
        socket.send(packet);
    }

    @Override
    public TcpSegment receive(Duration timeout) throws IOException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }

        socket.setSoTimeout(timeoutMillis(timeout));
        byte[] bytes = new byte[MAX_UDP_PAYLOAD_LENGTH];
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
        socket.receive(packet);

        Inet4Address sourceAddress = ipv4(packet.getAddress());
        byte[] encoded = new byte[packet.getLength()];
        System.arraycopy(
                packet.getData(), packet.getOffset(), encoded, 0, packet.getLength());
        TcpSegment segment = codec.decode(encoded, sourceAddress, localAddress);
        if (segment.sourcePort() != packet.getPort()) {
            throw new IllegalArgumentException(
                    "TCP source port does not match UDP datagram source");
        }
        if (segment.destinationPort() != localPort()) {
            throw new IllegalArgumentException(
                    "TCP destination port does not match UDP transport");
        }
        return segment;
    }

    @Override
    public void close() {
        socket.close();
    }

    private static int timeoutMillis(Duration timeout) {
        long wholeMillis;
        try {
            wholeMillis = timeout.toMillis();
        } catch (ArithmeticException overflow) {
            return Integer.MAX_VALUE;
        }
        if (wholeMillis <= 0) {
            return 1;
        }
        return (int) Math.min(wholeMillis, Integer.MAX_VALUE);
    }

    private static Inet4Address ipv4(InetAddress address) {
        if (!(address instanceof Inet4Address ipv4Address)) {
            throw new IllegalArgumentException("UDP source must be an IPv4 address");
        }
        return ipv4Address;
    }
}
