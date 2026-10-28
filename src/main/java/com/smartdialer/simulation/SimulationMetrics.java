package com.smartdialer.simulation;

import com.smartdialer.domain.enums.DialingMode;
import com.smartdialer.pacing.PacingDecision;
import com.smartdialer.safety.SafetyDecision;
import com.smartdialer.telecom.ProviderHealth;

import java.time.Instant;
import java.util.List;

public record SimulationMetrics(
    Long simulationId,
    String scenarioName,
    DialingMode dialingMode,
    String providerName,
    int totalAgents,
    int totalBorrowers,
    long totalCallsInitiated,
    long totalCallsAnswered,
    long totalCallsConnected,
    long totalCallsCompleted,
    long totalCallsFailed,
    long totalCallsCancelled,
    long abandonedCalls,
    double agentUtilizationPct,
    long totalAgentTalkSeconds,
    long totalAgentIdleSeconds,
    PacingDecision latestPacingDecision,
    SafetyDecision latestSafetyDecision,
    ProviderHealth providerHealth,
    int safetyApprovals,
    int safetyReductions,
    int safetyRejections,
    Instant startTime,
    Instant endTime,
    String executionSummary
) {}
