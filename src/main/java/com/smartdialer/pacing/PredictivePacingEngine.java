package com.smartdialer.pacing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Rule-based Predictive Pacing Engine.
 * Dynamically calculates the optimal call initiation rate based on expected agent turnover,
 * ringing exposure, smoothed answer probabilities, and telecom provider health.
 */
@Service
public class PredictivePacingEngine {

    private static final Logger log = LoggerFactory.getLogger(PredictivePacingEngine.class);

    private final AnswerRateSmoother answerRateSmoother;

    @Value("${smartdialer.pacing.safety-buffer-factor:0.85}")
    private double safetyBufferFactor = 0.85;

    public PredictivePacingEngine(AnswerRateSmoother answerRateSmoother) {
        this.answerRateSmoother = answerRateSmoother;
    }

    /**
     * Calculates the proposed call volume with full explainability.
     */
    public PacingDecision calculateProposedCalls(PacingInputs inputs) {
        long currentActive = inputs.dialingAgents() + inputs.connectedCalls() + inputs.ringingCalls();

        // 1. Calculate smoothed answer probability
        double smoothedAnswerRate = answerRateSmoother.calculateSmoothedAnswerRate(
            inputs.totalAnsweredCalls(),
            inputs.totalFinishedAttempts(),
            inputs.defaultAnswerRate(),
            inputs.minSampleSize()
        );

        // 2. Estimate agent churn during setup window (H seconds)
        double setupHorizon = Math.max(5.0, Math.min(30.0, inputs.avgSetupTimeSeconds()));
        double avgTalk = Math.max(30.0, inputs.avgTalkDurationSeconds());
        double turnoverRate = setupHorizon / avgTalk;
        double expectedFreedAgents = inputs.connectedCalls() * turnoverRate;

        // 3. Expected available capacity at the moment outbound calls are answered
        // Note: Subtract ringing calls as they are active outbound attempts in flight
        double expectedCapacity = inputs.availableAgents() + expectedFreedAgents - inputs.ringingCalls();

        // 4. If no expected capacity, propose 0 calls
        if (expectedCapacity <= 0) {
            String reason = String.format("Zero or negative net capacity (available: %d, expected freed: %.1f, ringing exposure: %d)",
                inputs.availableAgents(), expectedFreedAgents, inputs.ringingCalls());
            return PacingDecision.of(inputs.campaignId(), 0, smoothedAnswerRate, expectedCapacity,
                safetyBufferFactor, currentActive, inputs.availableAgents(), reason, false);
        }

        // 5. Calculate raw dials needed based on smoothed answer rate
        // E.g. If expected capacity = 3 and answer rate = 30%, raw = 3 / 0.3 = 10 dials
        double rawDials = expectedCapacity / smoothedAnswerRate;

        // 6. Apply conservative safety margin
        int proposed = (int) Math.floor(rawDials * safetyBufferFactor);

        // 7. Check telecom provider health throttling
        boolean throttled = false;
        StringBuilder reason = new StringBuilder();
        reason.append(String.format("Expected capacity: %.1f (avail: %d + freed: %.1f - ringing: %d). Smoothed P(ans): %.2f%%. Raw dials: %.1f. ",
            expectedCapacity, inputs.availableAgents(), expectedFreedAgents, inputs.ringingCalls(), smoothedAnswerRate * 100, rawDials));

        if (inputs.providerFailureRatePct() > 15.0) {
            throttled = true;
            double healthMultiplier = Math.max(0.1, 1.0 - (inputs.providerFailureRatePct() / 100.0));
            int throttledCalls = (int) Math.floor(proposed * healthMultiplier);
            reason.append(String.format("Provider degraded (fail rate: %.1f%%). Throttled from %d to %d. ",
                inputs.providerFailureRatePct(), proposed, throttledCalls));
            proposed = throttledCalls;
        }

        if (inputs.providerLatencyMs() > 600) {
            throttled = true;
            proposed = (int) Math.floor(proposed * 0.7);
            reason.append(String.format("High provider latency (%dms). Reduced to %d. ", inputs.providerLatencyMs(), proposed));
        }

        // 8. Ceiling cap to avoid extreme surges
        long maxBurstCap = Math.max(2, (long) (inputs.availableAgents() * 3.0 + 5));
        if (proposed > maxBurstCap) {
            reason.append(String.format("Capped at burst limit %d (was %d). ", maxBurstCap, proposed));
            proposed = (int) maxBurstCap;
        }

        reason.append(String.format("Final proposed calls: %d", proposed));

        log.debug("Pacing engine decision for campaign {}: {}", inputs.campaignId(), reason);

        return PacingDecision.of(
            inputs.campaignId(),
            Math.max(0, proposed),
            smoothedAnswerRate,
            expectedCapacity,
            safetyBufferFactor,
            currentActive,
            inputs.availableAgents(),
            reason.toString(),
            throttled
        );
    }
}
