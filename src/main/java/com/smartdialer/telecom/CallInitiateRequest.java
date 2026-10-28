package com.smartdialer.telecom;

import java.time.Instant;

public record CallInitiateRequest(
    String callExternalId,
    String idempotencyKey,
    String phoneNumber,
    Long campaignId,
    Long borrowerId,
    Long agentId
) {}
