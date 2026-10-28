package com.smartdialer.telecom;

public record CallInitiateResponse(
    boolean accepted,
    String providerCallId,
    String failureReason,
    long networkLatencyMs
) {
    public static CallInitiateResponse success(String providerCallId, long latencyMs) {
        return new CallInitiateResponse(true, providerCallId, null, latencyMs);
    }

    public static CallInitiateResponse rejected(String reason, long latencyMs) {
        return new CallInitiateResponse(false, null, reason, latencyMs);
    }
}
