package com.ouc.tcp.timer;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * RFC 6298 retransmission-timeout estimator.
 */
public final class RttEstimator {
    public static final Duration DEFAULT_INITIAL_RTO = Duration.ofSeconds(1);
    public static final Duration DEFAULT_MINIMUM_RTO = Duration.ofSeconds(1);
    public static final Duration DEFAULT_MAXIMUM_RTO = Duration.ofSeconds(60);
    public static final Duration DEFAULT_CLOCK_GRANULARITY = Duration.ofMillis(1);

    private final long clockGranularityNanos;
    private final long minimumRtoNanos;
    private final long maximumRtoNanos;

    private Long smoothedRttNanos;
    private Long rttVariationNanos;
    private long retransmissionTimeoutNanos;

    public RttEstimator() {
        this(
                DEFAULT_INITIAL_RTO,
                DEFAULT_MINIMUM_RTO,
                DEFAULT_MAXIMUM_RTO,
                DEFAULT_CLOCK_GRANULARITY);
    }

    public RttEstimator(
            Duration initialRto,
            Duration minimumRto,
            Duration maximumRto,
            Duration clockGranularity) {
        long initialNanos = positiveNanos(initialRto, "initialRto");
        minimumRtoNanos = positiveNanos(minimumRto, "minimumRto");
        maximumRtoNanos = positiveNanos(maximumRto, "maximumRto");
        clockGranularityNanos =
                positiveNanos(clockGranularity, "clockGranularity");
        if (minimumRtoNanos > maximumRtoNanos) {
            throw new IllegalArgumentException("minimumRto must not exceed maximumRto");
        }
        retransmissionTimeoutNanos = clamp(initialNanos);
    }

    public void recordSample(Duration sample) {
        long sampleNanos = positiveNanos(sample, "sample");
        if (smoothedRttNanos == null) {
            smoothedRttNanos = sampleNanos;
            rttVariationNanos = sampleNanos / 2;
        } else {
            long error = absoluteDifference(smoothedRttNanos, sampleNanos);
            rttVariationNanos =
                    weightedAverage(rttVariationNanos, 3, error, 1, 4);
            smoothedRttNanos =
                    weightedAverage(smoothedRttNanos, 7, sampleNanos, 1, 8);
        }

        long variationTerm = saturatedMultiply(rttVariationNanos, 4);
        long margin = Math.max(clockGranularityNanos, variationTerm);
        retransmissionTimeoutNanos =
                clamp(saturatedAdd(smoothedRttNanos, margin));
    }

    public void backOff() {
        retransmissionTimeoutNanos =
                clamp(saturatedMultiply(retransmissionTimeoutNanos, 2));
    }

    public Duration retransmissionTimeout() {
        return Duration.ofNanos(retransmissionTimeoutNanos);
    }

    public Optional<Duration> smoothedRtt() {
        return optionalDuration(smoothedRttNanos);
    }

    public Optional<Duration> rttVariation() {
        return optionalDuration(rttVariationNanos);
    }

    private long clamp(long value) {
        return Math.max(minimumRtoNanos, Math.min(value, maximumRtoNanos));
    }

    private static long positiveNanos(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        long nanos;
        try {
            nanos = duration.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(name + " is too large", overflow);
        }
        if (nanos <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return nanos;
    }

    private static long absoluteDifference(long left, long right) {
        return left >= right ? left - right : right - left;
    }

    private static long weightedAverage(
            long first, long firstWeight, long second, long secondWeight, long divisor) {
        long firstTerm = saturatedMultiply(first, firstWeight);
        long secondTerm = saturatedMultiply(second, secondWeight);
        return saturatedAdd(firstTerm, secondTerm) / divisor;
    }

    private static long saturatedMultiply(long value, long multiplier) {
        if (value > Long.MAX_VALUE / multiplier) {
            return Long.MAX_VALUE;
        }
        return value * multiplier;
    }

    private static long saturatedAdd(long left, long right) {
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static Optional<Duration> optionalDuration(Long nanos) {
        return nanos == null
                ? Optional.empty()
                : Optional.of(Duration.ofNanos(nanos));
    }
}
