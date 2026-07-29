package com.ouc.tcp.connection;

import com.ouc.tcp.core.TcpSegment;
import com.ouc.tcp.transport.SegmentTransport;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Objects;

/**
 * Runs an active or passive three-way handshake over a segment transport.
 */
public final class TcpHandshakeRunner {
    private final TcpConnectionLifecycle lifecycle;
    private final SegmentTransport transport;
    private final ControlRetryPolicy retryPolicy;

    public TcpHandshakeRunner(
            TcpConnectionLifecycle lifecycle,
            SegmentTransport transport,
            ControlRetryPolicy retryPolicy) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
    }

    public HandshakeResult activeOpen() throws IOException {
        send(lifecycle.connect());
        return awaitEstablished();
    }

    public HandshakeResult passiveOpen() throws IOException {
        lifecycle.listen();
        return awaitEstablished();
    }

    private HandshakeResult awaitEstablished() throws IOException {
        int timeoutCount = 0;
        int peerAdvertisedWindow = 0;
        while (timeoutCount < retryPolicy.maximumTimeouts()) {
            try {
                TcpSegment received = transport.receive(retryPolicy.timeout());
                LifecycleResult result = lifecycle.receive(received);
                if (!result.accepted()) {
                    continue;
                }
                peerAdvertisedWindow = received.advertisedWindow();
                send(result);
                if (lifecycle.state() == TcpState.ESTABLISHED) {
                    return new HandshakeResult(
                            lifecycle.sendNext(),
                            lifecycle.receiveNext(),
                            peerAdvertisedWindow);
                }
            } catch (SocketTimeoutException timeout) {
                timeoutCount++;
                if (timeoutCount < retryPolicy.maximumTimeouts()) {
                    TcpSegment retry = lifecycle
                            .retransmissionCandidate()
                            .orElse(null);
                    if (retry != null) {
                        transport.send(retry);
                    }
                }
            }
        }
        throw new SocketTimeoutException(
                "TCP handshake exceeded "
                        + retryPolicy.maximumTimeouts()
                        + " receive timeouts");
    }

    private void send(LifecycleResult result) throws IOException {
        for (TcpSegment segment : result.transmissions()) {
            transport.send(segment);
        }
    }
}
