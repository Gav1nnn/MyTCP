package com.ouc.tcp.transport;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic actions keyed by one-based outbound transmission number.
 */
public final class FaultPlan {
    private final Map<Long, FaultAction> actions;

    public FaultPlan(Map<Long, FaultAction> actions) {
        Objects.requireNonNull(actions, "actions");
        Map<Long, FaultAction> validated = new HashMap<>();
        actions.forEach((transmission, action) -> {
            if (transmission == null || transmission < 1) {
                throw new IllegalArgumentException(
                        "fault transmission numbers must be positive");
            }
            validated.put(
                    transmission,
                    Objects.requireNonNull(action, "action"));
        });
        this.actions = Collections.unmodifiableMap(validated);
    }

    public static FaultPlan none() {
        return new FaultPlan(Map.of());
    }

    public static FaultPlan parse(String specification) {
        Objects.requireNonNull(specification, "specification");
        if (specification.isBlank()) {
            return none();
        }
        Map<Long, FaultAction> parsed = new HashMap<>();
        for (String entry : specification.split(",")) {
            String[] fields = entry.trim().split("=", -1);
            if (fields.length != 2) {
                throw invalidSpecification(specification);
            }
            long transmission;
            FaultAction action;
            try {
                transmission = Long.parseLong(fields[0]);
                action = FaultAction.valueOf(
                        fields[1].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                throw invalidSpecification(specification);
            }
            if (transmission < 1
                    || action == FaultAction.PASS
                    || parsed.putIfAbsent(transmission, action) != null) {
                throw invalidSpecification(specification);
            }
        }
        return new FaultPlan(parsed);
    }

    public FaultAction actionFor(long transmissionNumber) {
        if (transmissionNumber < 1) {
            throw new IllegalArgumentException(
                    "transmissionNumber must be positive");
        }
        return actions.getOrDefault(
                transmissionNumber,
                FaultAction.PASS);
    }

    public boolean isEmpty() {
        return actions.isEmpty();
    }

    private static IllegalArgumentException invalidSpecification(
            String specification) {
        return new IllegalArgumentException(
                "invalid fault plan: "
                        + specification
                        + " (expected N=drop|corrupt|duplicate|reorder)");
    }
}
