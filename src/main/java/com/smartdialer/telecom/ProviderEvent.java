package com.smartdialer.telecom;

import java.time.Instant;

public record ProviderEvent(
    String providerEventId,
    String providerCallId,
    String callExternalId,
    String eventType, // INITIATED, RINGING, ANSWERED, CONNECTED, COMPLETED, FAILED, CANCELLED
    String failureReason,
    Long durationSeconds,
    Instant timestamp,
    String rawPayload
) {
    public static ProviderEvent of(String providerEventId, String providerCallId, String callExternalId,
                                   String eventType, String failureReason, Long durationSeconds) {
        return new ProviderEvent(
            providerEventId,
            providerCallId,
            callExternalId,
            eventType,
            failureReason,
            durationSeconds,
            Instant.now(),
            "{eventType: " + eventType + ", callId: " + providerCallId + "}"
        );
    }
}
