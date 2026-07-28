package com.ouc.tcp.core;

import java.util.Objects;

/**
 * Unsigned 32-bit TCP sequence number with serial-number arithmetic.
 *
 * <p>Ordering is meaningful only when two values are separated by less than
 * half of the sequence space. Values exactly {@code 2^31} apart are
 * intentionally unordered.</p>
 */
public final class SequenceNumber32 {
    public static final long MODULUS = 1L << 32;
    public static final long MAX_VALUE = MODULUS - 1;
    public static final long HALF_RANGE = 1L << 31;

    private final long value;

    private SequenceNumber32(long value) {
        this.value = value;
    }

    public static SequenceNumber32 of(long value) {
        if (value < 0 || value > MAX_VALUE) {
            throw new IllegalArgumentException("value must be an unsigned 32-bit number");
        }
        return new SequenceNumber32(value);
    }

    public static SequenceNumber32 fromIntBits(int value) {
        return new SequenceNumber32(Integer.toUnsignedLong(value));
    }

    public long toLong() {
        return value;
    }

    public int toIntBits() {
        return (int) value;
    }

    public SequenceNumber32 add(long byteCount) {
        if (byteCount < 0) {
            throw new IllegalArgumentException("byteCount must not be negative");
        }
        long normalizedIncrement = byteCount % MODULUS;
        return new SequenceNumber32((value + normalizedIncrement) % MODULUS);
    }

    /**
     * Returns the forward distance from this sequence number to {@code other}.
     */
    public long distanceTo(SequenceNumber32 other) {
        Objects.requireNonNull(other, "other");
        return (other.value - value + MODULUS) % MODULUS;
    }

    public boolean isBefore(SequenceNumber32 other) {
        long distance = distanceTo(other);
        return distance != 0 && distance < HALF_RANGE;
    }

    public boolean isBeforeOrEqual(SequenceNumber32 other) {
        return equals(other) || isBefore(other);
    }

    public boolean isAfter(SequenceNumber32 other) {
        return Objects.requireNonNull(other, "other").isBefore(this);
    }

    public boolean isAfterOrEqual(SequenceNumber32 other) {
        return equals(other) || isAfter(other);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof SequenceNumber32 that && value == that.value);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }

    @Override
    public String toString() {
        return Long.toUnsignedString(value);
    }
}
