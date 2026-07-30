package com.ouc.tcp.trace;

import com.ouc.tcp.checksum.TcpChecksum;
import com.ouc.tcp.connection.TcpState;
import com.ouc.tcp.core.SequenceNumber32;
import com.ouc.tcp.core.TcpFlag;
import com.ouc.tcp.core.TcpSegment;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextProtocolTraceTest {
    @Test
    void writesStableSegmentStateAndSenderRecords() throws Exception {
        StringWriter text = new StringWriter();
        try (TextProtocolTrace trace = new TextProtocolTrace(text)) {
            trace.segment(
                    SegmentDirection.SEND,
                    TcpChecksum.apply(new TcpSegment(
                            ipv4("192.0.2.1"),
                            ipv4("198.51.100.2"),
                            19_001,
                            19_002,
                            100,
                            500,
                            Set.of(TcpFlag.ACK, TcpFlag.FIN),
                            32_768,
                            0,
                            new byte[] {1, 2, 3})));
            trace.stateTransition(
                    TcpState.ESTABLISHED,
                    TcpState.FIN_WAIT_1);
            trace.senderSnapshot(
                    TcpState.FIN_WAIT_1,
                    new SenderSnapshot(
                            SequenceNumber32.of(100),
                            SequenceNumber32.of(103),
                            3,
                            3_600,
                            65_535,
                            32_768,
                            Duration.ofSeconds(1)));
        }

        assertEquals(
                """
                event=segment direction=SEND seq=100 ack=500 len=3 flags=FIN,ACK rwnd=32768 checksum=43211
                event=state from=ESTABLISHED to=FIN_WAIT_1
                event=sender state=FIN_WAIT_1 snd_una=100 snd_nxt=103 flight=3 cwnd=3600 ssthresh=65535 rwnd=32768 rto_ms=1000
                """,
                text.toString());
    }

    private static Inet4Address ipv4(String address) throws Exception {
        return (Inet4Address) InetAddress.getByName(address);
    }
}
