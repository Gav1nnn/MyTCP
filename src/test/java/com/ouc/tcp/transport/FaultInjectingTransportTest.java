package com.ouc.tcp.transport;

import com.ouc.tcp.checksum.TcpChecksum;
import com.ouc.tcp.core.TcpFlag;
import com.ouc.tcp.core.TcpSegment;
import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FaultInjectingTransportTest {
    @Test
    void appliesDropCorruptionDuplicationAndReordering()
            throws Exception {
        RecordingTransport recording = new RecordingTransport();
        FaultInjectingTransport transport =
                new FaultInjectingTransport(
                        recording,
                        FaultPlan.parse(
                                "1=drop,2=corrupt,3=duplicate,4=reorder"));

        for (int sequence = 1; sequence <= 5; sequence++) {
            transport.send(segment(sequence));
        }

        assertEquals(
                List.of(2L, 3L, 3L, 5L, 4L),
                recording.sent().stream()
                        .map(TcpSegment::sequenceNumber)
                        .toList());
        assertFalse(TcpChecksum.isValid(recording.sent().get(0)));
        assertEquals(5, transport.transmissionCount());
    }

    private static TcpSegment segment(long sequenceNumber)
            throws Exception {
        return TcpChecksum.apply(new TcpSegment(
                ipv4("192.0.2.1"),
                ipv4("198.51.100.2"),
                19_001,
                19_002,
                sequenceNumber,
                500,
                Set.of(TcpFlag.ACK),
                32_768,
                0,
                new byte[] {1}));
    }

    private static Inet4Address ipv4(String address) throws Exception {
        return (Inet4Address) InetAddress.getByName(address);
    }

    private static final class RecordingTransport
            implements SegmentTransport {
        private final List<TcpSegment> sent = new ArrayList<>();

        @Override
        public void send(TcpSegment segment) {
            sent.add(segment);
        }

        @Override
        public TcpSegment receive(Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }

        private List<TcpSegment> sent() {
            return List.copyOf(sent);
        }
    }
}
