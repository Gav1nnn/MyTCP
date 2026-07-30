package com.ouc.tcp.endpoint;

import com.ouc.tcp.connection.ConnectionConfig;
import com.ouc.tcp.connection.ControlRetryPolicy;
import com.ouc.tcp.connection.HandshakeResult;
import com.ouc.tcp.connection.LifecycleResult;
import com.ouc.tcp.connection.TcpConnectionLifecycle;
import com.ouc.tcp.connection.TcpHandshakeRunner;
import com.ouc.tcp.connection.TcpState;
import com.ouc.tcp.core.TcpFlag;
import com.ouc.tcp.core.TcpSegment;
import com.ouc.tcp.transport.SegmentTransport;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

/**
 * Coordinates handshake, established data transfer, and orderly close.
 */
public final class TcpSession implements AutoCloseable {
    private final SegmentTransport transport;
    private final TcpConnectionLifecycle lifecycle;
    private final StandaloneTcpEndpoint endpoint;

    private TcpSession(
            ConnectionConfig connectionConfig,
            SegmentTransport transport,
            TcpConnectionLifecycle lifecycle,
            HandshakeResult handshake,
            EndpointTuning tuning) {
        this.transport = transport;
        this.lifecycle = lifecycle;
        endpoint = new StandaloneTcpEndpoint(
                new EndpointConfig(
                        connectionConfig.localAddress(),
                        connectionConfig.remoteAddress(),
                        connectionConfig.localPort(),
                        connectionConfig.remotePort(),
                        handshake.sendNext(),
                        handshake.receiveNext(),
                        connectionConfig.receiveWindow(),
                        handshake.peerAdvertisedWindow(),
                        tuning.maximumSegmentSize(),
                        tuning.initialCongestionWindow(),
                        tuning.initialSlowStartThreshold()),
                transport);
    }

    public static TcpSession openActive(
            ConnectionConfig connectionConfig,
            SegmentTransport transport,
            ControlRetryPolicy retryPolicy,
            EndpointTuning tuning) throws IOException {
        return open(
                connectionConfig, transport, retryPolicy, tuning, true);
    }

    public static TcpSession openPassive(
            ConnectionConfig connectionConfig,
            SegmentTransport transport,
            ControlRetryPolicy retryPolicy,
            EndpointTuning tuning) throws IOException {
        return open(
                connectionConfig, transport, retryPolicy, tuning, false);
    }

    public void send(byte[] data) throws IOException {
        requireState(TcpState.ESTABLISHED);
        endpoint.send(data);
    }

    public SessionEvent poll(Duration timeout) throws IOException {
        TcpSegment segment = transport.receive(timeout);
        byte[] delivered = new byte[0];

        if (segment.hasFlag(TcpFlag.ACK)) {
            delivered = endpoint.process(segment).deliveredBytes();
        } else if (segment.payloadLength() > 0) {
            delivered = endpoint.process(segment).deliveredBytes();
        }

        if (lifecycleNeeds(segment)) {
            if (segment.hasFlag(TcpFlag.FIN)
                    && canSynchronizeReceiveSequenceSpace()) {
                lifecycle.synchronizeReceiveSequenceSpace(
                        endpoint.receiveNext());
            }
            send(lifecycle.receive(segment));
        }
        return new SessionEvent(segment, delivered, lifecycle.state());
    }

    public void initiateClose() throws IOException {
        if (!endpoint.sendComplete()) {
            throw new IllegalStateException(
                    "connection cannot close with outstanding application data");
        }
        if (lifecycle.state() == TcpState.ESTABLISHED) {
            synchronizeSequenceSpace();
        }
        send(lifecycle.close(endpoint.sendNext(), lifecycle.receiveNext()));
    }

    public void expireTimeWait() {
        lifecycle.expireTimeWait();
    }

    public boolean sendComplete() {
        return endpoint.sendComplete();
    }

    public TcpState state() {
        return lifecycle.state();
    }

    public long congestionWindow() {
        return endpoint.congestionWindow();
    }

    @Override
    public void close() {
        endpoint.close();
    }

    private static TcpSession open(
            ConnectionConfig connectionConfig,
            SegmentTransport transport,
            ControlRetryPolicy retryPolicy,
            EndpointTuning tuning,
            boolean active) throws IOException {
        Objects.requireNonNull(connectionConfig, "connectionConfig");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        Objects.requireNonNull(tuning, "tuning");
        TcpConnectionLifecycle lifecycle =
                new TcpConnectionLifecycle(connectionConfig);
        TcpHandshakeRunner handshakeRunner =
                new TcpHandshakeRunner(lifecycle, transport, retryPolicy);
        HandshakeResult handshake = active
                ? handshakeRunner.activeOpen()
                : handshakeRunner.passiveOpen();
        return new TcpSession(
                connectionConfig, transport, lifecycle, handshake, tuning);
    }

    private boolean lifecycleNeeds(TcpSegment segment) {
        return segment.hasFlag(TcpFlag.SYN)
                || segment.hasFlag(TcpFlag.FIN)
                || segment.hasFlag(TcpFlag.RST)
                || lifecycle.state() == TcpState.FIN_WAIT_1
                || lifecycle.state() == TcpState.CLOSING
                || lifecycle.state() == TcpState.LAST_ACK;
    }

    private void synchronizeSequenceSpace() {
        lifecycle.synchronizeEstablishedSequenceSpace(
                endpoint.sendNext(), endpoint.receiveNext());
    }

    private boolean canSynchronizeReceiveSequenceSpace() {
        return lifecycle.state() == TcpState.ESTABLISHED
                || lifecycle.state() == TcpState.FIN_WAIT_1
                || lifecycle.state() == TcpState.FIN_WAIT_2
                || lifecycle.state() == TcpState.CLOSE_WAIT;
    }

    private void send(LifecycleResult result) throws IOException {
        for (TcpSegment segment : result.transmissions()) {
            transport.send(segment);
        }
    }

    private void requireState(TcpState required) {
        if (lifecycle.state() != required) {
            throw new IllegalStateException(
                    "expected state " + required + " but was " + lifecycle.state());
        }
    }
}
