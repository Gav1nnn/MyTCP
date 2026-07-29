package com.ouc.tcp.endpoint;

import com.ouc.tcp.core.AckDisposition;
import com.ouc.tcp.core.ReceiveDisposition;
import com.ouc.tcp.core.TcpSegment;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of processing one segment from the standalone transport.
 */
public record EndpointPollResult(
        TcpSegment receivedSegment,
        byte[] deliveredBytes,
        Optional<ReceiveDisposition> receiveDisposition,
        Optional<AckDisposition> acknowledgmentDisposition) {

    public EndpointPollResult {
        Objects.requireNonNull(receivedSegment, "receivedSegment");
        deliveredBytes = Objects.requireNonNull(
                deliveredBytes, "deliveredBytes").clone();
        Objects.requireNonNull(receiveDisposition, "receiveDisposition");
        Objects.requireNonNull(
                acknowledgmentDisposition, "acknowledgmentDisposition");
    }

    @Override
    public byte[] deliveredBytes() {
        return deliveredBytes.clone();
    }
}
