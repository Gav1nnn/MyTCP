package com.ouc.tcp.test;

import com.ouc.tcp.adapter.FrameworkPacketCodec;
import com.ouc.tcp.adapter.IntegerPayloadCodec;
import com.ouc.tcp.checksum.TcpChecksum;
import com.ouc.tcp.client.TCP_Receiver_ADT;
import com.ouc.tcp.config.Constant;
import com.ouc.tcp.core.ReceiveResult;
import com.ouc.tcp.core.SequenceNumber32;
import com.ouc.tcp.core.TcpFlag;
import com.ouc.tcp.core.TcpReceiverEngine;
import com.ouc.tcp.core.TcpSegment;
import com.ouc.tcp.message.TCP_PACKET;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.Inet4Address;
import java.util.Set;

/**
 * Teaching-framework receiver backed by the RFC-aligned transport core.
 */
public final class TCP_Receiver extends TCP_Receiver_ADT {
    private static final long INITIAL_SEQUENCE_NUMBER = 1;
    private static final int RECEIVE_WINDOW_BYTES = 32 * 1024;

    private final FrameworkPacketCodec packetCodec = new FrameworkPacketCodec();
    private final TcpReceiverEngine receiverEngine = new TcpReceiverEngine(
            SequenceNumber32.of(INITIAL_SEQUENCE_NUMBER),
            RECEIVE_WINDOW_BYTES);

    public TCP_Receiver() {
        super();
        super.initTCP_Receiver(this);
    }

    @Override
    public void rdt_recv(TCP_PACKET packet) {
        TcpSegment segment = packetCodec.decode(packet);
        ReceiveResult result = receiverEngine.receive(segment);

        if (result.deliveredBytes().length > 0) {
            dataQueue.add(IntegerPayloadCodec.decode(result.deliveredBytes()));
            deliver_data();
        }
        if (result.acknowledgmentRequired()) {
            reply(packetCodec.encode(acknowledgmentFor(segment, result)));
        }
    }

    @Override
    public void deliver_data() {
        File output = new File("recvData.txt");
        try (BufferedWriter writer =
                new BufferedWriter(new FileWriter(output, true))) {
            while (!dataQueue.isEmpty()) {
                int[] data = dataQueue.poll();
                for (int value : data) {
                    writer.write(Integer.toString(value));
                    writer.newLine();
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("failed to deliver received data", failure);
        }
    }

    @Override
    public void reply(TCP_PACKET packet) {
        packet.getTcpH().setTh_eflag((byte) 7);
        client.send(packet);
    }

    private TcpSegment acknowledgmentFor(
            TcpSegment received, ReceiveResult result) {
        return TcpChecksum.apply(new TcpSegment(
                ipv4(Constant.LocalAddr),
                received.sourceAddress(),
                localPort,
                received.sourcePort(),
                INITIAL_SEQUENCE_NUMBER,
                result.acknowledgmentNumber().toLong(),
                Set.of(TcpFlag.ACK),
                result.advertisedWindow(),
                0,
                new byte[0]));
    }

    private static Inet4Address ipv4(java.net.InetAddress address) {
        if (!(address instanceof Inet4Address ipv4Address)) {
            throw new IllegalStateException(
                    "the teaching framework must run with an IPv4 local address");
        }
        return ipv4Address;
    }
}
