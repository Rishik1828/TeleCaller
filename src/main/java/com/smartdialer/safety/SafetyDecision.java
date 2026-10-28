package com.smartdialer.safety;

import java.time.Instant;

public record SafetyDecision(
    Outcome outcome,
    int approvedCalls,
    int requestedCalls,
    String reason,
    long availableAgents,
    long ringingCalls,
    long totalActiveCalls,
    Instant timestamp
) {
    public enum Outcome {
        APPROVED,
        REDUCED,
        REJECTED,
        FALLBACK_PROGRESSIVE
    }

    public static SafetyDecision approved(int count, String reason, long avail, long ringing, long active) {
        return new SafetyDecision(Outcome.APPROVED, count, count, reason, avail, ringing, active, Instant.now());
    }

    public static SafetyDecision reduced(int approved, int requested, String reason, long avail, long ringing, long active) {
        return new SafetyDecision(Outcome.REDUCED, approved, requested, reason, avail, ringing, active, Instant.now());
    }

    public static SafetyDecision rejected(int requested, String reason, long avail, long ringing, long active) {
        return new SafetyDecision(Outcome.REJECTED, 0, requested, reason, avail, ringing, active, Instant.now());
    }

    public static SafetyDecision fallbackProgressive(int approved, int requested, String reason, long avail, long ringing, long active) {
        return new SafetyDecision(Outcome.FALLBACK_PROGRESSIVE, approved, requested, reason, avail, ringing, active, Instant.now());
    }
}
