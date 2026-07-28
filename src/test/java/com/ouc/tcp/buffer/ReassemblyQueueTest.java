package com.ouc.tcp.buffer;

import com.ouc.tcp.core.SequenceNumber32;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReassemblyQueueTest {
    @Test
    void retainsFirstBytesForOverlappingSegments() {
        ReassemblyQueue queue = new ReassemblyQueue();
        SequenceNumber32 leftEdge = SequenceNumber32.of(100);

        queue.insert(SequenceNumber32.of(102), new byte[] {9, 9, 5}, leftEdge, 8);
        ReassemblyQueue.InsertionResult overlap =
                queue.insert(leftEdge, new byte[] {1, 2, 3, 4}, leftEdge, 8);
        ReassemblyQueue.DrainResult drain = queue.drainContiguous(leftEdge);

        assertEquals(2, overlap.newBytes());
        assertEquals(2, overlap.duplicateBytes());
        assertArrayEquals(new byte[] {1, 2, 9, 9, 5}, drain.deliveredBytes());
        assertEquals(105, drain.nextExpected().toLong());
    }

    @Test
    void trimsBytesOutsideBothWindowEdges() {
        ReassemblyQueue queue = new ReassemblyQueue();
        SequenceNumber32 leftEdge = SequenceNumber32.of(100);

        ReassemblyQueue.InsertionResult insertion = queue.insert(
                SequenceNumber32.of(98),
                new byte[] {8, 9, 1, 2, 3, 4, 5, 6},
                leftEdge,
                4);
        ReassemblyQueue.DrainResult drain = queue.drainContiguous(leftEdge);

        assertEquals(4, insertion.newBytes());
        assertEquals(2, insertion.duplicateBytes());
        assertEquals(2, insertion.outsideWindowBytes());
        assertArrayEquals(new byte[] {1, 2, 3, 4}, drain.deliveredBytes());
    }

    @Test
    void drainsContiguousBytesAcrossSequenceWrap() {
        ReassemblyQueue queue = new ReassemblyQueue();
        SequenceNumber32 leftEdge = SequenceNumber32.of(0xFFFF_FFFEL);

        queue.insert(SequenceNumber32.of(0), new byte[] {3, 4}, leftEdge, 8);
        queue.insert(leftEdge, new byte[] {1, 2}, leftEdge, 8);
        ReassemblyQueue.DrainResult drain = queue.drainContiguous(leftEdge);

        assertArrayEquals(new byte[] {1, 2, 3, 4}, drain.deliveredBytes());
        assertEquals(2, drain.nextExpected().toLong());
        assertEquals(0, queue.bufferedByteCount());
    }
}
