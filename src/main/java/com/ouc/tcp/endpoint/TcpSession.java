package com.ouc.tcp.endpoint;

import com.ouc.tcp.connection.ConnectionConfig;
import com.ouc.tcp.connection.ControlRetryPolicy;
import com.ouc.tcp.connection.HandshakeResult;
import com.ouc.tcp.connection.LifecycleResult;
import com.ouc.tcp.connection.SessionTiming;
import com.ouc.tcp.connection.TcpConnectionLifecycle;
import com.ouc.tcp.connection.TcpHandshakeRunner;
import com.ouc.tcp.connection.TcpState;
import com.ouc.tcp.core.TcpFlag;
import com.ouc.tcp.core.TcpSegment;
import com.ouc.tcp.timer.ExecutorScheduler;
import com.ouc.tcp.timer.RetransmissionTimer;
import com.ouc.tcp.transport.SegmentTransport;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coordinates handshake, established data transfer, and orderly close.
 */
public final class TcpSession implements AutoCloseable {
    private final SegmentTransport transport;
    private final TcpConnectionLifecycle lifecycle;
    private final StandaloneTcpEndpoint endpoint;
    private final ControlRetryPolicy controlRetryPolicy;
    private final ExecutorScheduler controlScheduler;
    private final RetransmissionTimer controlRetransmissionTimer;
    private final RetransmissionTimer timeWaitTimer;
    private final SessionTiming sessionTiming;
    private final Object controlTimerLock = new Object();
    private final AtomicReference<IOException> asynchronousControlFailure =
            new AtomicReference<>();

    private Duration controlRetransmissionTimeout;
    private int controlTimeoutCount;

    private TcpSession(
            ConnectionConfig connectionConfig,
            SegmentTransport transport,
            TcpConnectionLifecycle lifecycle,
            HandshakeResult handshake,
            ControlRetryPolicy controlRetryPolicy,
            SessionTiming sessionTiming,
            EndpointTuning tuning) {
        this.transport = transport;
        this.lifecycle = lifecycle;
        this.controlRetryPolicy = controlRetryPolicy;
        this.sessionTiming = sessionTiming;
        controlScheduler = new ExecutorScheduler(
                "standalone-tcp-control-" + connectionConfig.localPort());
        controlRetransmissionTimer =
                new RetransmissionTimer(controlScheduler);
        timeWaitTimer = new RetransmissionTimer(controlScheduler);
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
                        tuning.initialWindowAfterHandshake(
                                handshake.localControlRetransmitted()),
                        tuning.initialSlowStartThreshold(),
                        handshake.initialDataRetransmissionTimeout()),
                transport);
    }

    public static TcpSession openActive(
            ConnectionConfig connectionConfig,
            SegmentTransport transport,
            ControlRetryPolicy retryPolicy,
            EndpointTuning tuning) throws IOException {
        return openActive(
                connectionConfig,
                transport,
                retryPolicy,
                SessionTiming.loopbackDefaults(),
                tuning);
    }

    public static TcpSession openActive(
            ConnectionConfig connectionConfig,
            SegmentTransport transport,
            ControlRetryPolicy retryPolicy,
            SessionTiming sessionTiming,
            EndpointTuning tuning) throws IOException {
        return open(
                connectionConfig,
                transport,
                retryPolicy,
                sessionTiming,
                tuning,
                true);
    }

    public static TcpSession openPassive(
            ConnectionConfig connectionConfig,
            SegmentTransport transport,
            ControlRetryPolicy retryPolicy,
            EndpointTuning tuning) throws IOException {
        return openPassive(
                connectionConfig,
                transport,
                retryPolicy,
                SessionTiming.loopbackDefaults(),
                tuning);
    }

    public static TcpSession openPassive(
            ConnectionConfig connectionConfig,
            SegmentTransport transport,
            ControlRetryPolicy retryPolicy,
            SessionTiming sessionTiming,
            EndpointTuning tuning) throws IOException {
        return open(
                connectionConfig,
                transport,
                retryPolicy,
                sessionTiming,
                tuning,
                false);
    }

    public void send(byte[] data) throws IOException {
        checkAsynchronousControlFailure();
        requireState(TcpState.ESTABLISHED);
        endpoint.send(data);
    }

    public SessionEvent poll(Duration timeout) throws IOException {
        checkAsynchronousControlFailure();
        TcpSegment segment = transport.receive(timeout);
        byte[] delivered = new byte[0];

        if (segment.hasFlag(TcpFlag.ACK)) {
            delivered = endpoint.process(segment).deliveredBytes();
        } else if (segment.payloadLength() > 0) {
            delivered = endpoint.process(segment).deliveredBytes();
        }

        if (lifecycleNeeds(segment)) {
            TcpState previousState = lifecycle.state();
            if (segment.hasFlag(TcpFlag.FIN)
                    && canSynchronizeReceiveSequenceSpace()) {
                lifecycle.synchronizeReceiveSequenceSpace(
                        endpoint.receiveNext());
            }
            LifecycleResult lifecycleResult =
                    lifecycle.receive(segment);
            send(lifecycleResult);
            stopControlTimerIfAcknowledged();
            updateTimeWaitTimer(
                    previousState,
                    segment,
                    lifecycleResult.accepted());
        }
        checkAsynchronousControlFailure();
        return new SessionEvent(segment, delivered, lifecycle.state());
    }

    public void initiateClose() throws IOException {
        checkAsynchronousControlFailure();
        if (!endpoint.sendComplete()) {
            throw new IllegalStateException(
                    "connection cannot close with outstanding application data");
        }
        if (lifecycle.state() == TcpState.ESTABLISHED) {
            synchronizeSequenceSpace();
        }
        send(lifecycle.close(endpoint.sendNext(), lifecycle.receiveNext()));
        startControlRetransmissionTimer();
    }

    public void expireTimeWait() {
        timeWaitTimer.stop();
        if (lifecycle.state() == TcpState.TIME_WAIT) {
            lifecycle.expireTimeWait();
        }
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

    public Duration retransmissionTimeout() {
        return endpoint.retransmissionTimeout();
    }

    @Override
    public void close() {
        controlRetransmissionTimer.stop();
        timeWaitTimer.stop();
        controlScheduler.close();
        endpoint.close();
    }

    private static TcpSession open(
            ConnectionConfig connectionConfig,
            SegmentTransport transport,
            ControlRetryPolicy retryPolicy,
            SessionTiming sessionTiming,
            EndpointTuning tuning,
            boolean active) throws IOException {
        Objects.requireNonNull(connectionConfig, "connectionConfig");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        Objects.requireNonNull(sessionTiming, "sessionTiming");
        Objects.requireNonNull(tuning, "tuning");
        TcpConnectionLifecycle lifecycle =
                new TcpConnectionLifecycle(connectionConfig);
        TcpHandshakeRunner handshakeRunner =
                new TcpHandshakeRunner(lifecycle, transport, retryPolicy);
        HandshakeResult handshake = active
                ? handshakeRunner.activeOpen()
                : handshakeRunner.passiveOpen();
        return new TcpSession(
                connectionConfig,
                transport,
                lifecycle,
                handshake,
                retryPolicy,
                sessionTiming,
                tuning);
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

    private void startControlRetransmissionTimer() {
        synchronized (controlTimerLock) {
            controlTimeoutCount = 0;
            controlRetransmissionTimeout = controlRetryPolicy.timeout();
            scheduleControlTimeout();
        }
    }

    private void stopControlTimerIfAcknowledged() {
        if (lifecycle.retransmissionCandidate().isPresent()) {
            return;
        }
        synchronized (controlTimerLock) {
            controlRetransmissionTimer.stop();
            controlRetransmissionTimeout = null;
            controlTimeoutCount = 0;
        }
    }

    private void updateTimeWaitTimer(
            TcpState previousState,
            TcpSegment received,
            boolean accepted) {
        if (lifecycle.state() != TcpState.TIME_WAIT) {
            return;
        }
        if (previousState != TcpState.TIME_WAIT
                || (accepted && received.hasFlag(TcpFlag.FIN))) {
            timeWaitTimer.startOrRestart(
                    sessionTiming.timeWaitDuration(),
                    this::expireTimeWaitIfActive);
        }
    }

    private void expireTimeWaitIfActive() {
        if (lifecycle.state() == TcpState.TIME_WAIT) {
            lifecycle.expireTimeWait();
        }
    }

    private void scheduleControlTimeout() {
        controlRetransmissionTimer.startOrRestart(
                controlRetransmissionTimeout,
                this::onControlRetransmissionTimeout);
    }

    private void onControlRetransmissionTimeout() {
        TcpSegment retransmission;
        synchronized (controlTimerLock) {
            retransmission = lifecycle.retransmissionCandidate().orElse(null);
            if (retransmission == null) {
                controlRetransmissionTimeout = null;
                controlTimeoutCount = 0;
                return;
            }
            controlTimeoutCount++;
            if (controlTimeoutCount
                    >= controlRetryPolicy.maximumTimeouts()) {
                asynchronousControlFailure.compareAndSet(
                        null,
                        new IOException(
                                "TCP control retransmission exceeded "
                                        + controlRetryPolicy.maximumTimeouts()
                                        + " timeouts"));
                controlRetransmissionTimeout = null;
                return;
            }
            controlRetransmissionTimeout =
                    controlRetryPolicy.backOff(
                            controlRetransmissionTimeout);
            scheduleControlTimeout();
        }
        try {
            transport.send(retransmission);
        } catch (IOException failure) {
            asynchronousControlFailure.compareAndSet(null, failure);
            controlRetransmissionTimer.stop();
        }
    }

    private void checkAsynchronousControlFailure() throws IOException {
        IOException failure = asynchronousControlFailure.get();
        if (failure != null) {
            throw new IOException(
                    "asynchronous TCP control transmission failed",
                    failure);
        }
    }

    private void requireState(TcpState required) {
        if (lifecycle.state() != required) {
            throw new IllegalStateException(
                    "expected state " + required + " but was " + lifecycle.state());
        }
    }
}
