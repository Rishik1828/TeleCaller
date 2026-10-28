package com.smartdialer.pacing;

public record PacingInputs(
    Long campaignId,
    long availableAgents,
    long dialingAgents,
    long connectedCalls,
    long ringingCalls,
    double defaultAnswerRate,
    int minSampleSize,
    long totalFinishedAttempts,
    long totalAnsweredCalls,
    double avgTalkDurationSeconds,
    double avgSetupTimeSeconds,
    double providerFailureRatePct,
    long providerLatencyMs
) {}
