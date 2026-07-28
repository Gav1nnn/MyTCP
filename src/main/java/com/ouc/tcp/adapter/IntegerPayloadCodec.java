package com.ouc.tcp.adapter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Converts the teaching framework's integer application data to TCP bytes.
 */
public final class IntegerPayloadCodec {
    public static final int BYTES_PER_INTEGER = Integer.BYTES;

    private IntegerPayloadCodec() {
    }

    public static byte[] encode(int[] values) {
        Objects.requireNonNull(values, "values");
        ByteBuffer buffer = ByteBuffer
                .allocate(Math.multiplyExact(values.length, BYTES_PER_INTEGER))
                .order(ByteOrder.BIG_ENDIAN);
        for (int value : values) {
            buffer.putInt(value);
        }
        return buffer.array();
    }

    public static int[] decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length % BYTES_PER_INTEGER != 0) {
            throw new IllegalArgumentException(
                    "encoded integer payload length must be a multiple of four bytes");
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int[] values = new int[bytes.length / BYTES_PER_INTEGER];
        for (int index = 0; index < values.length; index++) {
            values[index] = buffer.getInt();
        }
        return values;
    }
}
