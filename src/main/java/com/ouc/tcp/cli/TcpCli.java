package com.ouc.tcp.cli;

import com.ouc.tcp.connection.ConnectionConfig;
import com.ouc.tcp.connection.ControlRetryPolicy;
import com.ouc.tcp.connection.TcpState;
import com.ouc.tcp.core.SequenceNumber32;
import com.ouc.tcp.endpoint.EndpointTuning;
import com.ouc.tcp.endpoint.SessionEvent;
import com.ouc.tcp.endpoint.TcpSession;
import com.ouc.tcp.transport.UdpSegmentTransport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;

/**
 * Minimal standalone client/server file transfer entry point.
 */
public final class TcpCli {
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(1);
    private static final int CONTROL_TIMEOUT_LIMIT = 8;
    private static final int RECEIVE_WINDOW = 32_768;

    private TcpCli() {
    }

    public static void main(String[] arguments) {
        try {
            run(arguments);
        } catch (Exception failure) {
            System.err.println("mytcp: " + failure.getMessage());
            System.exit(1);
        }
    }

    public static void run(String[] arguments) throws Exception {
        CliArguments parsed = CliArguments.parse(arguments);
        if (parsed.mode() == Mode.CLIENT) {
            runClient(parsed);
        } else {
            runServer(parsed);
        }
    }

    private static void runClient(CliArguments arguments) throws Exception {
        byte[] input = Files.readAllBytes(arguments.file());
        Inet4Address loopback = loopback();
        try (UdpSegmentTransport transport =
                        new UdpSegmentTransport(loopback, arguments.localPort());
                TcpSession session = TcpSession.openActive(
                        connection(arguments, loopback),
                        transport,
                        retryPolicy(),
                        EndpointTuning.defaults())) {
            session.send(input);
            while (!session.sendComplete()) {
                pollIgnoringIdleTimeout(session);
            }

            session.initiateClose();
            while (session.state() != TcpState.CLOSED) {
                pollIgnoringIdleTimeout(session);
            }
        }
        System.out.println("sent " + input.length + " bytes");
    }

    private static void runServer(CliArguments arguments) throws Exception {
        Inet4Address loopback = loopback();
        long receivedBytes = 0;
        try (UdpSegmentTransport transport =
                        new UdpSegmentTransport(loopback, arguments.localPort());
                TcpSession session = TcpSession.openPassive(
                        connection(arguments, loopback),
                        transport,
                        retryPolicy(),
                        EndpointTuning.defaults());
                OutputStream output = Files.newOutputStream(arguments.file())) {
            while (session.state() == TcpState.ESTABLISHED) {
                try {
                    SessionEvent event = session.poll(POLL_TIMEOUT);
                    byte[] delivered = event.deliveredBytes();
                    output.write(delivered);
                    receivedBytes += delivered.length;
                } catch (SocketTimeoutException idle) {
                    // Continue listening while the peer or its RTO timer is active.
                }
            }

            if (session.state() != TcpState.CLOSE_WAIT) {
                throw new IOException(
                        "connection left data transfer in state " + session.state());
            }
            session.initiateClose();
            while (session.state() != TcpState.CLOSED) {
                pollIgnoringIdleTimeout(session);
            }
        }
        System.out.println("received " + receivedBytes + " bytes");
    }

    private static void pollIgnoringIdleTimeout(TcpSession session)
            throws IOException {
        try {
            session.poll(POLL_TIMEOUT);
        } catch (SocketTimeoutException idle) {
            // Data/control timers own retransmission; polling only drives input.
        }
    }

    private static ConnectionConfig connection(
            CliArguments arguments, Inet4Address loopback) {
        long initialSequence = Integer.toUnsignedLong(
                new SecureRandom().nextInt());
        return new ConnectionConfig(
                loopback,
                loopback,
                arguments.localPort(),
                arguments.peerPort(),
                SequenceNumber32.of(initialSequence),
                RECEIVE_WINDOW);
    }

    private static ControlRetryPolicy retryPolicy() {
        return new ControlRetryPolicy(
                CONTROL_TIMEOUT, CONTROL_TIMEOUT_LIMIT);
    }

    private static Inet4Address loopback() throws Exception {
        return (Inet4Address) InetAddress.getByName("127.0.0.1");
    }

    private enum Mode {
        CLIENT,
        SERVER
    }

    private record CliArguments(
            Mode mode, int localPort, int peerPort, Path file) {

        private static CliArguments parse(String[] arguments) {
            if (arguments == null || arguments.length != 4) {
                throw usage();
            }
            Mode mode = switch (arguments[0]) {
                case "client" -> Mode.CLIENT;
                case "server" -> Mode.SERVER;
                default -> throw usage();
            };
            int localPort = port(arguments[1], "local-port");
            int peerPort = port(arguments[2], "peer-port");
            Path file = Path.of(arguments[3]).toAbsolutePath().normalize();
            if (mode == Mode.CLIENT && !Files.isRegularFile(file)) {
                throw new IllegalArgumentException(
                        "input file does not exist: " + file);
            }
            return new CliArguments(mode, localPort, peerPort, file);
        }

        private static int port(String text, String name) {
            try {
                int port = Integer.parseInt(text);
                if (port < 1 || port > 65_535) {
                    throw new NumberFormatException();
                }
                return port;
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException(
                        name + " must be between 1 and 65535");
            }
        }

        private static IllegalArgumentException usage() {
            return new IllegalArgumentException(
                    "usage: mytcp <client|server> "
                            + "<local-port> <peer-port> <file>");
        }
    }
}
