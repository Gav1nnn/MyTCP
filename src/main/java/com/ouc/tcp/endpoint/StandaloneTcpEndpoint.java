package com.ouc.tcp.endpoint;

import com.ouc.tcp.checksum.TcpChecksum;
import com.ouc.tcp.core.AckDisposition;
import com.ouc.tcp.core.AckProcessingResult;
import com.ouc.tcp.core.ReceiveDisposition;
import com.ouc.tcp.core.ReceiveResult;
import com.ouc.tcp.core.ReceiverConfig;
import com.ouc.tcp.core.SenderConfig;
import com.ouc.tcp.core.SequenceNumber32;
import com.ouc.tcp.core.TcpFlag;
import com.ouc.tcp.core.TcpReceiverEngine;
import com.ouc.tcp.core.TcpSegment;
import com.ouc.tcp.core.TcpSenderEngine;
import com.ouc.tcp.timer.Clock;
import com.ouc.tcp.timer.ExecutorScheduler;
import com.ouc.tcp.transport.SegmentTransport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Standalone established TCP endpoint backed by the protocol engines.
 */
public final class StandaloneTcpEndpoint implements AutoCloseable {
    private final EndpointConfig config;
    private final SegmentTransport transport;
    private final ExecutorScheduler scheduler;
    private final TcpSenderEngine sender;
    private final TcpReceiverEngine receiver;
    private final AtomicReference<IOException> asynchronousFailure =
            new AtomicReference<>();

    public StandaloneTcpEndpoint(
            EndpointConfig config,
            SegmentTransport transport) {
        this.config = Objects.requireNonNull(config, "config");
        this.transport = Objects.requireNonNull(transport, "transport");
        scheduler = new ExecutorScheduler(
                "standalone-tcp-timer-" + config.localPort());
        sender = new TcpSenderEngine(
                new SenderConfig(
                        config.localAddress(),
                        config.remoteAddress(),
                        config.localPort(),
                        config.remotePort(),
                        config.initialSendNext(),
                        config.initialReceiveNext(),
                        config.receiveWindow(),
                        config.peerReceiveWindow(),
                        config.maximumSegmentSize(),
                        config.initialCongestionWindow(),
                        config.initialSlowStartThreshold()),
                Clock.system(),
                scheduler,
                this::sendFromTimer);
        receiver = new TcpReceiverEngine(new ReceiverConfig(
                config.localAddress(),
                config.remoteAddress(),
                config.localPort(),
                config.remotePort(),
                config.initialReceiveNext(),
                config.receiveWindow()));
    }

    public synchronized void send(byte[] data) throws IOException {
        checkAsynchronousFailure();
        sendAll(sender.queueData(Objects.requireNonNull(data, "data")));
    }

    public synchronized EndpointPollResult poll(Duration timeout)
            throws IOException {
        checkAsynchronousFailure();
        return process(transport.receive(timeout));
    }

    public synchronized EndpointPollResult process(TcpSegment segment)
            throws IOException {
        checkAsynchronousFailure();
        Objects.requireNonNull(segment, "segment");
        byte[] delivered = new byte[0];
        Optional<ReceiveDisposition> receiveDisposition = Optional.empty();
        if (segment.payloadLength() > 0) {
            ReceiveResult result = receiver.receive(segment);
            delivered = result.deliveredBytes();
            receiveDisposition = Optional.of(result.disposition());
            sender.updateReceiveState(
                    result.acknowledgmentNumber(),
                    result.advertisedWindow());
            if (result.acknowledgmentRequired()) {
                transport.send(acknowledgment(result));
            }
        }

        Optional<AckDisposition> acknowledgmentDisposition = Optional.empty();
        if (segment.hasFlag(TcpFlag.ACK)) {
            AckProcessingResult result = sender.receiveAcknowledgment(segment);
            acknowledgmentDisposition = Optional.of(result.disposition());
            sendAll(result.transmissions());
        }
        return new EndpointPollResult(
                segment,
                delivered,
                receiveDisposition,
                acknowledgmentDisposition);
    }

    public synchronized boolean sendComplete() {
        return sender.pendingByteCount() == 0 && sender.flightSize() == 0;
    }

    public synchronized long flightSize() {
        return sender.flightSize();
    }

    public synchronized long congestionWindow() {
        return sender.congestionWindow();
    }

    public synchronized int sendWindow() {
        return sender.sendWindow();
    }

    public synchronized SequenceNumber32 sendNext() {
        return sender.sendNext();
    }

    public synchronized SequenceNumber32 receiveNext() {
        return receiver.receiveNext();
    }

    @Override
    public void close() {
        scheduler.close();
        transport.close();
    }

    private TcpSegment acknowledgment(ReceiveResult result) {
        return TcpChecksum.apply(new TcpSegment(
                config.localAddress(),
                config.remoteAddress(),
                config.localPort(),
                config.remotePort(),
                sender.sendNext().toLong(),
                result.acknowledgmentNumber().toLong(),
                Set.of(TcpFlag.ACK),
                result.advertisedWindow(),
                0,
                new byte[0]));
    }

    private void sendAll(List<TcpSegment> segments) throws IOException {
        for (TcpSegment segment : segments) {
            transport.send(segment);
        }
    }

    private void sendFromTimer(TcpSegment segment) {
        try {
            transport.send(segment);
        } catch (IOException failure) {
            asynchronousFailure.compareAndSet(null, failure);
            throw new UncheckedIOException(failure);
        }
    }

    private void checkAsynchronousFailure() throws IOException {
        IOException failure = asynchronousFailure.get();
        if (failure != null) {
            throw new IOException(
                    "asynchronous TCP transmission failed", failure);
        }
    }
}
