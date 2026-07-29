package com.ouc.tcp.connection;

import com.ouc.tcp.checksum.TcpChecksum;
import com.ouc.tcp.core.SequenceNumber32;
import com.ouc.tcp.core.TcpFlag;
import com.ouc.tcp.core.TcpSegment;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * RFC-style connection establishment and orderly close state machine.
 *
 * <p>Established data transfer remains owned by the sender and receiver
 * engines. The runtime synchronizes their current sequence variables before
 * initiating close, so SYN and FIN consume sequence space in one coherent
 * lifecycle.</p>
 */
public final class TcpConnectionLifecycle {
    private final ConnectionConfig config;
    private TcpState state = TcpState.CLOSED;
    private SequenceNumber32 sendNext;
    private SequenceNumber32 receiveNext;

    public TcpConnectionLifecycle(ConnectionConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        sendNext = config.initialSendSequence();
    }

    public synchronized LifecycleResult listen() {
        requireState(TcpState.CLOSED);
        TcpState previous = state;
        state = TcpState.LISTEN;
        return result(true, previous, List.of());
    }

    public synchronized LifecycleResult connect() {
        requireState(TcpState.CLOSED);
        TcpState previous = state;
        TcpSegment syn = control(sendNext, SequenceNumber32.of(0), Set.of(TcpFlag.SYN));
        sendNext = sendNext.add(1);
        state = TcpState.SYN_SENT;
        return result(true, previous, List.of(syn));
    }

    public synchronized LifecycleResult receive(TcpSegment segment) {
        Objects.requireNonNull(segment, "segment");
        TcpState previous = state;
        if (!matchesConnection(segment) || !validChecksum(segment)) {
            return result(false, previous, List.of());
        }
        if (segment.hasFlag(TcpFlag.RST)) {
            state = TcpState.CLOSED;
            return result(true, previous, List.of());
        }

        return switch (state) {
            case LISTEN -> receiveInListen(segment, previous);
            case SYN_SENT -> receiveInSynSent(segment, previous);
            case SYN_RECEIVED -> receiveInSynReceived(segment, previous);
            case ESTABLISHED -> receiveInEstablished(segment, previous);
            case FIN_WAIT_1 -> receiveInFinWaitOne(segment, previous);
            case FIN_WAIT_2 -> receiveFin(segment, previous, TcpState.TIME_WAIT);
            case CLOSING -> receiveClosingAck(segment, previous);
            case CLOSE_WAIT, LAST_ACK -> receiveLastAck(segment, previous);
            case TIME_WAIT -> receiveInTimeWait(segment, previous);
            case CLOSED -> result(false, previous, List.of());
        };
    }

    public synchronized LifecycleResult close(
            SequenceNumber32 currentSendNext,
            SequenceNumber32 currentReceiveNext) {
        Objects.requireNonNull(currentSendNext, "currentSendNext");
        Objects.requireNonNull(currentReceiveNext, "currentReceiveNext");
        if (state != TcpState.ESTABLISHED && state != TcpState.CLOSE_WAIT) {
            throw new IllegalStateException("connection cannot close from " + state);
        }
        sendNext = currentSendNext;
        receiveNext = currentReceiveNext;
        TcpState previous = state;
        TcpSegment fin = control(
                sendNext, receiveNext, Set.of(TcpFlag.FIN, TcpFlag.ACK));
        sendNext = sendNext.add(1);
        state = previous == TcpState.ESTABLISHED
                ? TcpState.FIN_WAIT_1
                : TcpState.LAST_ACK;
        return result(true, previous, List.of(fin));
    }

    public synchronized void synchronizeEstablishedSequenceSpace(
            SequenceNumber32 currentSendNext,
            SequenceNumber32 currentReceiveNext) {
        Objects.requireNonNull(currentSendNext, "currentSendNext");
        Objects.requireNonNull(currentReceiveNext, "currentReceiveNext");
        if (state != TcpState.ESTABLISHED && state != TcpState.CLOSE_WAIT) {
            throw new IllegalStateException(
                    "sequence space cannot be synchronized from " + state);
        }
        sendNext = currentSendNext;
        receiveNext = currentReceiveNext;
    }

    public synchronized LifecycleResult expireTimeWait() {
        requireState(TcpState.TIME_WAIT);
        TcpState previous = state;
        state = TcpState.CLOSED;
        return result(true, previous, List.of());
    }

    public synchronized TcpState state() {
        return state;
    }

    public synchronized SequenceNumber32 sendNext() {
        return sendNext;
    }

    public synchronized SequenceNumber32 receiveNext() {
        if (receiveNext == null) {
            throw new IllegalStateException("peer sequence space is not synchronized");
        }
        return receiveNext;
    }

    private LifecycleResult receiveInListen(TcpSegment segment, TcpState previous) {
        if (!segment.hasFlag(TcpFlag.SYN) || segment.hasFlag(TcpFlag.ACK)) {
            return result(false, previous, List.of());
        }
        receiveNext = SequenceNumber32.of(segment.sequenceNumber()).add(1);
        TcpSegment synAck = control(
                sendNext, receiveNext, Set.of(TcpFlag.SYN, TcpFlag.ACK));
        sendNext = sendNext.add(1);
        state = TcpState.SYN_RECEIVED;
        return result(true, previous, List.of(synAck));
    }

    private LifecycleResult receiveInSynSent(TcpSegment segment, TcpState previous) {
        if (!segment.hasFlag(TcpFlag.SYN)
                || !segment.hasFlag(TcpFlag.ACK)
                || segment.acknowledgmentNumber() != sendNext.toLong()) {
            return result(false, previous, List.of());
        }
        receiveNext = SequenceNumber32.of(segment.sequenceNumber()).add(1);
        state = TcpState.ESTABLISHED;
        return result(true, previous, List.of(
                control(sendNext, receiveNext, Set.of(TcpFlag.ACK))));
    }

    private LifecycleResult receiveInSynReceived(
            TcpSegment segment, TcpState previous) {
        if (!acceptableAck(segment) || segment.sequenceNumber() != receiveNext.toLong()) {
            return result(false, previous, List.of());
        }
        state = TcpState.ESTABLISHED;
        return result(true, previous, List.of());
    }

    private LifecycleResult receiveInEstablished(
            TcpSegment segment, TcpState previous) {
        if (!segment.hasFlag(TcpFlag.FIN)
                || segment.sequenceNumber() != receiveNext.toLong()) {
            return result(false, previous, List.of());
        }
        receiveNext = receiveNext.add(1);
        state = TcpState.CLOSE_WAIT;
        return result(true, previous, List.of(
                control(sendNext, receiveNext, Set.of(TcpFlag.ACK))));
    }

    private LifecycleResult receiveInFinWaitOne(
            TcpSegment segment, TcpState previous) {
        boolean acknowledgesFin = acceptableAck(segment);
        boolean carriesExpectedFin = segment.hasFlag(TcpFlag.FIN)
                && segment.sequenceNumber() == receiveNext.toLong();
        if (!acknowledgesFin && !carriesExpectedFin) {
            return result(false, previous, List.of());
        }

        List<TcpSegment> transmissions = List.of();
        if (carriesExpectedFin) {
            receiveNext = receiveNext.add(1);
            transmissions = List.of(
                    control(sendNext, receiveNext, Set.of(TcpFlag.ACK)));
            state = acknowledgesFin ? TcpState.TIME_WAIT : TcpState.CLOSING;
        } else {
            state = TcpState.FIN_WAIT_2;
        }
        return result(true, previous, transmissions);
    }

    private LifecycleResult receiveFin(
            TcpSegment segment, TcpState previous, TcpState nextState) {
        if (!segment.hasFlag(TcpFlag.FIN)
                || segment.sequenceNumber() != receiveNext.toLong()) {
            return result(false, previous, List.of());
        }
        receiveNext = receiveNext.add(1);
        state = nextState;
        return result(true, previous, List.of(
                control(sendNext, receiveNext, Set.of(TcpFlag.ACK))));
    }

    private LifecycleResult receiveClosingAck(
            TcpSegment segment, TcpState previous) {
        if (!acceptableAck(segment)) {
            return result(false, previous, List.of());
        }
        state = TcpState.TIME_WAIT;
        return result(true, previous, List.of());
    }

    private LifecycleResult receiveLastAck(
            TcpSegment segment, TcpState previous) {
        if (state != TcpState.LAST_ACK || !acceptableAck(segment)) {
            return result(false, previous, List.of());
        }
        state = TcpState.CLOSED;
        return result(true, previous, List.of());
    }

    private LifecycleResult receiveInTimeWait(
            TcpSegment segment, TcpState previous) {
        if (!segment.hasFlag(TcpFlag.FIN)) {
            return result(false, previous, List.of());
        }
        return result(true, previous, List.of(
                control(sendNext, receiveNext, Set.of(TcpFlag.ACK))));
    }

    private boolean acceptableAck(TcpSegment segment) {
        return segment.hasFlag(TcpFlag.ACK)
                && segment.acknowledgmentNumber() == sendNext.toLong();
    }

    private TcpSegment control(
            SequenceNumber32 sequence,
            SequenceNumber32 acknowledgment,
            Set<TcpFlag> flags) {
        return TcpChecksum.apply(new TcpSegment(
                config.localAddress(),
                config.remoteAddress(),
                config.localPort(),
                config.remotePort(),
                sequence.toLong(),
                acknowledgment.toLong(),
                flags,
                config.receiveWindow(),
                0,
                new byte[0]));
    }

    private boolean matchesConnection(TcpSegment segment) {
        return segment.sourceAddress().equals(config.remoteAddress())
                && segment.destinationAddress().equals(config.localAddress())
                && segment.sourcePort() == config.remotePort()
                && segment.destinationPort() == config.localPort();
    }

    private static boolean validChecksum(TcpSegment segment) {
        try {
            return TcpChecksum.isValid(segment);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private LifecycleResult result(
            boolean accepted,
            TcpState previous,
            List<TcpSegment> transmissions) {
        return new LifecycleResult(accepted, previous, state, transmissions);
    }

    private void requireState(TcpState required) {
        if (state != required) {
            throw new IllegalStateException(
                    "expected state " + required + " but was " + state);
        }
    }
}
