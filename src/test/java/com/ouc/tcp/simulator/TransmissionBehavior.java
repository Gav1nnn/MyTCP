package com.ouc.tcp.simulator;

import com.ouc.tcp.core.TcpSegment;

import java.util.List;

/**
 * Maps one transmission to zero or more scheduled deliveries.
 */
@FunctionalInterface
public interface TransmissionBehavior {
    List<ScheduledDelivery> apply(TcpSegment segment);
}
