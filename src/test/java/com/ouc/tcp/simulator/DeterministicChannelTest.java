package com.ouc.tcp.simulator;

import com.ouc.tcp.core.TcpFlag;
import com.ouc.tcp.core.TcpSegment;
import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DeterministicChannelTest {
    @Test
    void deliversNormallyAndRecordsTransmissions() throws Exception {
        Fixture fixture = new Fixture();
        TcpSegment segment = segment(1, new byte[] {1});

        fixture.channel.send(segment);
        assertEquals(List.of(), fixture.delivered);

        fixture.scheduler.runReady();
        assertEquals(List.of(segment), fixture.delivered);
        assertEquals(List.of(segment), fixture.channel.transmissions());
    }

    @Test
    void dropsSelectedTransmission() throws Exception {
        Fixture fixture = new Fixture();
        fixture.channel.enqueue(TransmissionBehaviors.drop());

        fixture.channel.send(segment(1, new byte[] {1}));
        fixture.scheduler.advanceBy(Duration.ofDays(1));

        assertEquals(List.of(), fixture.delivered);
        assertEquals(1, fixture.channel.transmissions().size());
    }

    @Test
    void delaysAndReordersTransmissions() throws Exception {
        Fixture fixture = new Fixture();
        TcpSegment first = segment(1, new byte[] {1});
        TcpSegment second = segment(2, new byte[] {2});
        fixture.channel.enqueue(TransmissionBehaviors.deliverAfter(Duration.ofMillis(100)));
        fixture.channel.enqueue(TransmissionBehaviors.deliverAfter(Duration.ofMillis(10)));

        fixture.channel.send(first);
        fixture.channel.send(second);
        fixture.scheduler.advanceBy(Duration.ofMillis(10));
        assertEquals(List.of(second), fixture.delivered);

        fixture.scheduler.advanceBy(Duration.ofMillis(90));
        assertEquals(List.of(second, first), fixture.delivered);
    }

    @Test
    void duplicatesTransmissionAtConfiguredTimes() throws Exception {
        Fixture fixture = new Fixture();
        TcpSegment segment = segment(1, new byte[] {1});
        fixture.channel.enqueue(TransmissionBehaviors.duplicate(
                Duration.ofMillis(5), Duration.ofMillis(15)));

        fixture.channel.send(segment);
        fixture.scheduler.advanceBy(Duration.ofMillis(5));
        assertEquals(List.of(segment), fixture.delivered);

        fixture.scheduler.advanceBy(Duration.ofMillis(10));
        assertEquals(List.of(segment, segment), fixture.delivered);
    }

    @Test
    void corruptsDeliveredCopyWithoutChangingOriginal() throws Exception {
        Fixture fixture = new Fixture();
        TcpSegment original = segment(1, new byte[] {1, 2, 3});
        fixture.channel.enqueue(
                TransmissionBehaviors.corruptPayload(1, 0xFF, Duration.ZERO));

        fixture.channel.send(original);
        fixture.scheduler.runReady();

        assertArrayEquals(new byte[] {1, 2, 3}, original.payload());
        assertArrayEquals(new byte[] {1, (byte) 0xFD, 3}, fixture.delivered.get(0).payload());
        assertEquals(original.checksum(), fixture.delivered.get(0).checksum());
    }

    private static TcpSegment segment(long sequenceNumber, byte[] payload) throws Exception {
        return new TcpSegment(
                ipv4("198.51.100.1"),
                ipv4("198.51.100.2"),
                19001,
                19002,
                sequenceNumber,
                0,
                Set.of(TcpFlag.ACK),
                4096,
                123,
                payload);
    }

    private static Inet4Address ipv4(String address) throws Exception {
        return (Inet4Address) InetAddress.getByName(address);
    }

    private static final class Fixture {
        private final ManualClock clock = new ManualClock();
        private final DeterministicScheduler scheduler = new DeterministicScheduler(clock);
        private final List<TcpSegment> delivered = new ArrayList<>();
        private final DeterministicChannel channel =
                new DeterministicChannel(scheduler, delivered::add);
    }
}
