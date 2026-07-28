package com.ouc.tcp.congestion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RenoCongestionControllerTest {
    @Test
    void slowStartUsesAppropriateByteCounting() {
        RenoCongestionController controller =
                new RenoCongestionController(4, 4, 16);

        controller.onNewAcknowledgment(2);
        assertEquals(6, controller.congestionWindow());

        controller.onNewAcknowledgment(8);
        assertEquals(10, controller.congestionWindow());
        assertEquals(CongestionPhase.SLOW_START, controller.phase());
    }

    @Test
    void reachingThresholdMovesToCongestionAvoidance() {
        RenoCongestionController controller =
                new RenoCongestionController(4, 12, 16);

        controller.onNewAcknowledgment(4);

        assertEquals(16, controller.congestionWindow());
        assertEquals(
                CongestionPhase.CONGESTION_AVOIDANCE, controller.phase());
    }

    @Test
    void congestionAvoidanceAddsOneSmssPerWindowOfAcknowledgedBytes() {
        RenoCongestionController controller =
                new RenoCongestionController(4, 8, 8);

        controller.onNewAcknowledgment(4);
        assertEquals(8, controller.congestionWindow());

        controller.onNewAcknowledgment(4);
        assertEquals(12, controller.congestionWindow());

        controller.onNewAcknowledgment(8);
        assertEquals(12, controller.congestionWindow());
        controller.onNewAcknowledgment(4);
        assertEquals(16, controller.congestionWindow());
    }

    @Test
    void thirdDuplicateAckEntersFastRecoveryAndRequestsRetransmission() {
        RenoCongestionController controller =
                new RenoCongestionController(4, 40, 20);

        assertEquals(
                DuplicateAckAction.LIMITED_TRANSMIT,
                controller.onDuplicateAcknowledgment(40));
        assertEquals(
                DuplicateAckAction.LIMITED_TRANSMIT,
                controller.onDuplicateAcknowledgment(40));
        assertEquals(
                DuplicateAckAction.FAST_RETRANSMIT,
                controller.onDuplicateAcknowledgment(40));

        assertEquals(20, controller.slowStartThreshold());
        assertEquals(32, controller.congestionWindow());
        assertEquals(3, controller.duplicateAckCount());
        assertEquals(CongestionPhase.FAST_RECOVERY, controller.phase());
    }

    @Test
    void additionalDuplicateInflatesWindowAndNewAckDeflatesIt() {
        RenoCongestionController controller =
                new RenoCongestionController(4, 40, 20);
        controller.onDuplicateAcknowledgment(40);
        controller.onDuplicateAcknowledgment(40);
        controller.onDuplicateAcknowledgment(40);

        controller.onDuplicateAcknowledgment(40);
        assertEquals(36, controller.congestionWindow());

        controller.onNewAcknowledgment(4);
        assertEquals(20, controller.congestionWindow());
        assertEquals(0, controller.duplicateAckCount());
        assertEquals(
                CongestionPhase.CONGESTION_AVOIDANCE, controller.phase());
    }

    @Test
    void nonDuplicateAckBreaksDuplicateAckRun() {
        RenoCongestionController controller =
                new RenoCongestionController(4, 16, 32);
        controller.onDuplicateAcknowledgment(16);
        controller.onDuplicateAcknowledgment(16);

        controller.onNonDuplicateAcknowledgment();

        assertEquals(0, controller.duplicateAckCount());
        assertEquals(
                DuplicateAckAction.LIMITED_TRANSMIT,
                controller.onDuplicateAcknowledgment(16));
    }

    @Test
    void limitedTransmitBytesAreExcludedFromThresholdCalculation() {
        RenoCongestionController controller =
                new RenoCongestionController(4, 40, 80);
        controller.onDuplicateAcknowledgment(40);
        controller.recordLimitedTransmit(4);
        controller.onDuplicateAcknowledgment(44);
        controller.recordLimitedTransmit(4);

        controller.onDuplicateAcknowledgment(48);

        assertEquals(20, controller.slowStartThreshold());
    }

    @Test
    void timeoutStartsSlowStartAtOneSmssAndHalvesFlightSize() {
        RenoCongestionController controller =
                new RenoCongestionController(4, 40, 80);

        controller.onRetransmissionTimeout(40, false);

        assertEquals(20, controller.slowStartThreshold());
        assertEquals(4, controller.congestionWindow());
        assertEquals(CongestionPhase.SLOW_START, controller.phase());
    }

    @Test
    void repeatedTimeoutForSameSegmentKeepsThreshold() {
        RenoCongestionController controller =
                new RenoCongestionController(4, 40, 80);
        controller.onRetransmissionTimeout(40, false);

        controller.onRetransmissionTimeout(4, true);

        assertEquals(20, controller.slowStartThreshold());
        assertEquals(4, controller.congestionWindow());
    }
}
