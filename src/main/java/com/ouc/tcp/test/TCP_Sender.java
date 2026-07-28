package com.ouc.tcp.test;

import com.ouc.tcp.adapter.FrameworkPacketCodec;
import com.ouc.tcp.adapter.IntegerPayloadCodec;
import com.ouc.tcp.client.TCP_Sender_ADT;
import com.ouc.tcp.config.Constant;
import com.ouc.tcp.core.AckProcessingResult;
import com.ouc.tcp.core.SenderConfig;
import com.ouc.tcp.core.SequenceNumber32;
import com.ouc.tcp.core.TcpSegment;
import com.ouc.tcp.core.TcpSenderEngine;
import com.ouc.tcp.message.TCP_PACKET;
import com.ouc.tcp.timer.Clock;
import com.ouc.tcp.timer.ExecutorScheduler;

import java.net.Inet4Address;
import java.util.List;

/**
 * Teaching-framework sender backed by the RFC-aligned transport core.
 */
public final class TCP_Sender extends TCP_Sender_ADT {
    private static final long INITIAL_SEQUENCE_NUMBER = 1;
    private static final int SMSS_BYTES = 400;
    private static final int RECEIVE_WINDOW_BYTES = 32 * 1024;
    private static final long INITIAL_CONGESTION_WINDOW = 4L * SMSS_BYTES;
    private static final long INITIAL_SLOW_START_THRESHOLD = 64 * 1024L;

    private final FrameworkPacketCodec packetCodec = new FrameworkPacketCodec();
    private final ExecutorScheduler scheduler =
            new ExecutorScheduler("tcp-sender-timer");
    private final TcpSenderEngine senderEngine;

    public TCP_Sender() {
        super();
        super.initTCP_Sender(this);

        Inet4Address localAddress = ipv4(Constant.LocalAddr);
        Inet4Address remoteAddress = ipv4(destinAddr);
        senderEngine = new TcpSenderEngine(
                new SenderConfig(
                        localAddress,
                        remoteAddress,
                        localPort,
                        destinPort,
                        SequenceNumber32.of(INITIAL_SEQUENCE_NUMBER),
                        SequenceNumber32.of(INITIAL_SEQUENCE_NUMBER),
                        RECEIVE_WINDOW_BYTES,
                        RECEIVE_WINDOW_BYTES,
                        SMSS_BYTES,
                        INITIAL_CONGESTION_WINDOW,
                        INITIAL_SLOW_START_THRESHOLD),
                Clock.system(),
                scheduler,
                this::sendCoreSegment);
    }

    @Override
    public void rdt_send(int dataIndex, int[] appData) {
        sendAll(senderEngine.queueData(IntegerPayloadCodec.encode(appData)));
    }

    @Override
    public void udt_send(TCP_PACKET packet) {
        packet.getTcpH().setTh_eflag((byte) 7);
        client.send(packet);
    }

    @Override
    public void waitACK() {
        // ACK reception is event-driven by the framework's listener thread.
    }

    @Override
    public void recv(TCP_PACKET packet) {
        TcpSegment acknowledgment = packetCodec.decode(packet);
        AckProcessingResult result =
                senderEngine.receiveAcknowledgment(acknowledgment);
        sendAll(result.transmissions());
    }

    private void sendAll(List<TcpSegment> segments) {
        segments.forEach(this::sendCoreSegment);
    }

    private void sendCoreSegment(TcpSegment segment) {
        udt_send(packetCodec.encode(segment));
    }

    private static Inet4Address ipv4(java.net.InetAddress address) {
        if (!(address instanceof Inet4Address ipv4Address)) {
            throw new IllegalStateException(
                    "the teaching framework must run with an IPv4 local address");
        }
        return ipv4Address;
    }
}
