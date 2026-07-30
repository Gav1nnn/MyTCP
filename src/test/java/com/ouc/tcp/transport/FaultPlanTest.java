package com.ouc.tcp.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FaultPlanTest {
    @Test
    void parsesOneBasedDeterministicActions() {
        FaultPlan plan = FaultPlan.parse(
                "1=drop, 3=corrupt,5=duplicate,8=reorder");

        assertEquals(FaultAction.DROP, plan.actionFor(1));
        assertEquals(FaultAction.PASS, plan.actionFor(2));
        assertEquals(FaultAction.CORRUPT, plan.actionFor(3));
        assertEquals(FaultAction.DUPLICATE, plan.actionFor(5));
        assertEquals(FaultAction.REORDER, plan.actionFor(8));
    }

    @Test
    void rejectsMalformedOrDuplicateEntries() {
        assertThrows(
                IllegalArgumentException.class,
                () -> FaultPlan.parse("0=drop"));
        assertThrows(
                IllegalArgumentException.class,
                () -> FaultPlan.parse("1=drop,1=corrupt"));
        assertThrows(
                IllegalArgumentException.class,
                () -> FaultPlan.parse("1=pass"));
        assertThrows(
                IllegalArgumentException.class,
                () -> FaultPlan.parse("drop"));
    }
}
