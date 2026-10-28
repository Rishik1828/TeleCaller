package com.smartdialer.telecom;

import java.time.Instant;

public record ProviderHealth(
    String providerName,
    boolean isHealthy,
    double failureRatePct,
    long avgLatencyMs,
    long totalRequests,
    long failedRequests,
    Instant lastCheckTime,
    String statusMessage
) {
    public static ProviderHealth healthy(String providerName, double failureRatePct, long avgLatencyMs, long total, long failed) {
        return new ProviderHealth(providerName, true, failureRatePct, avgLatencyMs, total, failed, Instant.now(), "OPERATIONAL");
    }

    public static ProviderHealth degraded(String providerName, double failureRatePct, long avgLatencyMs, long total, long failed, String reason) {
        return new ProviderHealth(providerName, false, failureRatePct, avgLatencyMs, total, failed, Instant.now(), reason);
    }
}
