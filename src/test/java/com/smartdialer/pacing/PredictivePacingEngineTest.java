package com.smartdialer.pacing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PredictivePacingEngineTest {

    private final AnswerRateSmoother smoother = new AnswerRateSmoother();
    private final PredictivePacingEngine engine = new PredictivePacingEngine(smoother);

    @Test
    @DisplayName("Should propose reasonable call volume and provide full mathematical explainability")
    void testPacingCalculationAndExplainability() {
        PacingInputs inputs = new PacingInputs(
            1L,
            10,  // available agents
            0,   // dialing agents
            20,  // connected calls
            2,   // ringing calls
            0.30, // default prior answer rate
            20,  // min sample size
            100, // finished attempts
            35,  // answered calls (35% empirical)
            90.0, // avg talk duration
            15.0, // avg setup time
            2.0,  // provider failure %
            80    // provider latency
        );

        PacingDecision decision = engine.calculateProposedCalls(inputs);

        assertNotNull(decision);
        assertTrue(decision.proposedCalls() > 0, "Should propose positive dials");
        assertTrue(decision.explanationReason().contains("Expected capacity"));
        assertTrue(decision.explanationReason().contains("Smoothed P(ans)"));
        assertTrue(decision.estimatedAnswerRate() > 0.25 && decision.estimatedAnswerRate() < 0.40);
        assertFalse(decision.throttledByProviderHealth());
    }

    @Test
    @DisplayName("Should propose 0 calls when ringing exposure exceeds capacity")
    void testZeroCapacityWhenRingingExposureHigh() {
        PacingInputs inputs = new PacingInputs(
            1L,
            2,   // available agents
            0,   // dialing
            5,   // connected
            15,  // ringing calls (saturating system!)
            0.30, 20, 50, 15, 90.0, 15.0, 1.0, 50
        );

        PacingDecision decision = engine.calculateProposedCalls(inputs);

        assertEquals(0, decision.proposedCalls(), "Must propose 0 calls when ringing exposure exceeds net capacity");
        assertTrue(decision.explanationReason().contains("Zero or negative net capacity"));
    }

    @Test
    @DisplayName("Should automatically throttle dialing rate when provider failure rate is elevated")
    void testProviderFailureThrottling() {
        PacingInputs healthy = new PacingInputs(1L, 10, 0, 10, 1, 0.30, 20, 100, 30, 90.0, 15.0, 2.0, 70);
        PacingDecision healthyDec = engine.calculateProposedCalls(healthy);

        PacingInputs degraded = new PacingInputs(1L, 10, 0, 10, 1, 0.30, 20, 100, 30, 90.0, 15.0, 35.0, 700);
        PacingDecision degradedDec = engine.calculateProposedCalls(degraded);

        assertTrue(degradedDec.proposedCalls() < healthyDec.proposedCalls(), "Degraded provider must receive reduced pacing");
        assertTrue(degradedDec.throttledByProviderHealth());
        assertTrue(degradedDec.explanationReason().contains("Provider degraded"));
    }

    @Test
    @DisplayName("AnswerRateSmoother blends prior when sample size is small and converges as samples grow")
    void testAnswerRateSmoothing() {
        // Small sample: 1 answer in 2 attempts, prior 0.30 with weight 20
        double smoothSmall = smoother.calculateSmoothedAnswerRate(1, 2, 0.30, 20);
        // (1 + 20*0.3) / (2 + 20) = 7 / 22 = 0.318 (smooth, not 50%!)
        assertEquals(0.318, smoothSmall, 0.01);

        // Large sample: 300 answers in 1000 attempts (30%)
        double smoothLarge = smoother.calculateSmoothedAnswerRate(300, 1000, 0.50, 20);
        // (300 + 10) / (1000 + 20) = 310 / 1020 = 0.304 (empirical dominates prior)
        assertEquals(0.304, smoothLarge, 0.01);
    }
}
