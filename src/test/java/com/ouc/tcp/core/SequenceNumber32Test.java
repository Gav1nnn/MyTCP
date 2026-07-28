package com.ouc.tcp.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SequenceNumber32Test {
    @Test
    void convertsBetweenUnsignedValueAndSignedIntBits() {
        SequenceNumber32 sequence = SequenceNumber32.of(0xFFFF_FFFEL);

        assertEquals(-2, sequence.toIntBits());
        assertEquals(sequence, SequenceNumber32.fromIntBits(-2));
        assertEquals(0xFFFF_FFFEL, sequence.toLong());
    }

    @Test
    void wrapsAdditionAtEndOfSequenceSpace() {
        SequenceNumber32 sequence = SequenceNumber32.of(0xFFFF_FFF0L);

        assertEquals(0x10L, sequence.add(32).toLong());
        assertEquals(sequence, sequence.add(SequenceNumber32.MODULUS));
        assertThrows(IllegalArgumentException.class, () -> sequence.add(-1));
    }

    @Test
    void computesForwardDistanceAcrossWrap() {
        SequenceNumber32 nearEnd = SequenceNumber32.of(0xFFFF_FFF0L);
        SequenceNumber32 afterWrap = SequenceNumber32.of(0x20L);

        assertEquals(48, nearEnd.distanceTo(afterWrap));
        assertEquals(SequenceNumber32.MODULUS - 48, afterWrap.distanceTo(nearEnd));
    }

    @Test
    void ordersValuesAcrossWrapWithinHalfRange() {
        SequenceNumber32 beforeWrap = SequenceNumber32.of(0xFFFF_FFF0L);
        SequenceNumber32 afterWrap = SequenceNumber32.of(0x20L);

        assertTrue(beforeWrap.isBefore(afterWrap));
        assertTrue(beforeWrap.isBeforeOrEqual(afterWrap));
        assertTrue(afterWrap.isAfter(beforeWrap));
        assertFalse(afterWrap.isBefore(beforeWrap));
    }

    @Test
    void treatsHalfRangeSeparationAsUnordered() {
        SequenceNumber32 zero = SequenceNumber32.of(0);
        SequenceNumber32 halfRange = SequenceNumber32.of(SequenceNumber32.HALF_RANGE);

        assertFalse(zero.isBefore(halfRange));
        assertFalse(zero.isAfter(halfRange));
        assertFalse(halfRange.isBefore(zero));
        assertFalse(halfRange.isAfter(zero));
    }

    @Test
    void validatesConstructionRange() {
        assertThrows(IllegalArgumentException.class, () -> SequenceNumber32.of(-1));
        assertThrows(
                IllegalArgumentException.class,
                () -> SequenceNumber32.of(SequenceNumber32.MODULUS));
    }
}
