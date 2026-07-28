package com.ouc.tcp.adapter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IntegerPayloadCodecTest {
    @Test
    void encodesIntegersInNetworkByteOrder() {
        byte[] encoded = IntegerPayloadCodec.encode(
                new int[] {0x01020304, -1, Integer.MIN_VALUE});

        assertArrayEquals(
                new byte[] {
                    0x01, 0x02, 0x03, 0x04,
                    (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                    (byte) 0x80, 0x00, 0x00, 0x00
                },
                encoded);
    }

    @Test
    void roundTripsFullIntegerRange() {
        int[] values = {0, 1, -1, 104729, Integer.MIN_VALUE, Integer.MAX_VALUE};

        assertArrayEquals(values, IntegerPayloadCodec.decode(IntegerPayloadCodec.encode(values)));
    }

    @Test
    void supportsEmptyPayload() {
        assertArrayEquals(new byte[0], IntegerPayloadCodec.encode(new int[0]));
        assertArrayEquals(new int[0], IntegerPayloadCodec.decode(new byte[0]));
    }

    @Test
    void rejectsPartialIntegerPayload() {
        assertThrows(
                IllegalArgumentException.class,
                () -> IntegerPayloadCodec.decode(new byte[] {1, 2, 3}));
    }
}
