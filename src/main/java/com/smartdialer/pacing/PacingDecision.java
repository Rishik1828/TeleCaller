package com.smartdialer.pacing;

import java.time.Instant;

public record PacingDecision(
    Long campaignId,
    int proposedCalls,
    double estimatedAnswerRate,
    double expectedAgentCapacity,
    double safetyMargin,
    long currentActiveCalls,
    long availableAgents,
    String explanationReason,
    boolean throttledByProviderHealth,
    Instant timestamp
) {
    public static PacingDecision of(Long campaignId, int proposedCalls, double answerRate,
                                   double expectedCapacity, double safetyMargin,
                                   long activeCalls, long availableAgents, String reason,
                                   boolean throttled) {
        return new PacingDecision(
            campaignId,
            proposedCalls,
            Math.round(answerRate * 1000.0) / 1000.0,
            Math.round(expectedCapacity * 100.0) / 100.0,
            Math.round(safetyMargin * 100.0) / 100.0,
            activeCalls,
            availableAgents,
            reason,
            throttled,
            Instant.now()
        );
    }
}
