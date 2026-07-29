package com.ouc.tcp.transport;

import com.ouc.tcp.core.TcpSegment;

import java.io.IOException;
import java.time.Duration;

/**
 * Bidirectional transport boundary for encoded TCP segments.
 */
public interface SegmentTransport extends AutoCloseable {
    void send(TcpSegment segment) throws IOException;

    TcpSegment receive(Duration timeout) throws IOException;

    @Override
    void close();
}
