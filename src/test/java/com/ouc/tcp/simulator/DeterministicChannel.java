package com.ouc.tcp.simulator;

import com.ouc.tcp.core.TcpSegment;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * In-memory one-way channel with a queued behavior for each transmission.
 */
public final class DeterministicChannel {
    private static final TransmissionBehavior DEFAULT_BEHAVIOR =
            TransmissionBehaviors.deliverAfter(Duration.ZERO);

    private final DeterministicScheduler scheduler;
    private final Consumer<TcpSegment> receiver;
    private final Deque<TransmissionBehavior> queuedBehaviors = new ArrayDeque<>();
    private final List<TcpSegment> transmissions = new ArrayList<>();

    public DeterministicChannel(
            DeterministicScheduler scheduler, Consumer<TcpSegment> receiver) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.receiver = Objects.requireNonNull(receiver, "receiver");
    }

    public void enqueue(TransmissionBehavior behavior) {
        queuedBehaviors.addLast(Objects.requireNonNull(behavior, "behavior"));
    }

    public void send(TcpSegment segment) {
        Objects.requireNonNull(segment, "segment");
        transmissions.add(segment);
        TransmissionBehavior behavior = queuedBehaviors.isEmpty()
                ? DEFAULT_BEHAVIOR
                : queuedBehaviors.removeFirst();

        for (ScheduledDelivery delivery : behavior.apply(segment)) {
            scheduler.schedule(delivery.delay(), () -> receiver.accept(delivery.segment()));
        }
    }

    public List<TcpSegment> transmissions() {
        return List.copyOf(transmissions);
    }

    public int queuedBehaviorCount() {
        return queuedBehaviors.size();
    }
}
