package com.smartdialer.safety;

import com.smartdialer.domain.enums.AgentState;
import com.smartdialer.domain.enums.CallState;
import com.smartdialer.domain.enums.DialingMode;
import com.smartdialer.domain.enums.ProviderType;
import com.smartdialer.repository.AgentRepository;
import com.smartdialer.repository.CallRepository;
import com.smartdialer.telecom.ProviderHealth;
import com.smartdialer.telecom.TelecomProvider;
import com.smartdialer.telecom.TelecomProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Safety Controller — The final authority for every outbound call initiation.
 * Sits directly between the Pacing Engine and Call Allocator.
 * Cannot be bypassed by the pacing engine. Enforces a strict fail-closed safety policy.
 */
@Service
public class SafetyController {

    private static final Logger log = LoggerFactory.getLogger(SafetyController.class);

    private final AgentRepository agentRepository;
    private final CallRepository callRepository;
    private final TelecomProviderRegistry providerRegistry;
    private final SafetyLimits safetyLimits;

    // In-memory audit log of recent safety decisions
    private final List<SafetyDecision> recentDecisions = new CopyOnWriteArrayList<>();

    public SafetyController(AgentRepository agentRepository,
                            CallRepository callRepository,
                            TelecomProviderRegistry providerRegistry,
                            SafetyLimits safetyLimits) {
        this.agentRepository = agentRepository;
        this.callRepository = callRepository;
        this.providerRegistry = providerRegistry;
        this.safetyLimits = safetyLimits;
    }

    /**
     * Unbypassable gatekeeper. Validates pacing requests against hard real-time capacity and health limits.
     */
    public SafetyDecision evaluate(Long campaignId, DialingMode mode, int requestedCalls, ProviderType providerType) {
        if (requestedCalls <= 0) {
            SafetyDecision dec = SafetyDecision.rejected(0, "Pacing engine requested zero calls", 0, 0, 0);
            recordDecision(dec);
            return dec;
        }

        // 1. Fail-closed provider health verification
        TelecomProvider provider = providerRegistry.getProvider(providerType);
        ProviderHealth health;
        try {
            health = provider.getProviderHealth();
        } catch (Exception ex) {
            SafetyDecision dec = SafetyDecision.rejected(requestedCalls,
                "FAIL-CLOSED: Unable to verify provider health: " + ex.getMessage(), 0, 0, 0);
            recordDecision(dec);
            return dec;
        }

        if (!health.isHealthy() || health.failureRatePct() >= safetyLimits.getCircuitBreakerFailureThresholdPct()) {
            SafetyDecision dec = SafetyDecision.rejected(requestedCalls,
                String.format("CIRCUIT-BREAKER OPEN: Provider %s degraded (failure rate: %.1f%% >= threshold %.1f%%)",
                    providerType, health.failureRatePct(), safetyLimits.getCircuitBreakerFailureThresholdPct()),
                0, 0, 0);
            recordDecision(dec);
            return dec;
        }

        // 2. Fetch live database state
        long liveAvailableAgents = agentRepository.countByState(AgentState.AVAILABLE);
        long liveRingingCalls = callRepository.countByCampaignIdAndState(campaignId, CallState.RINGING);
        List<CallState> activeStates = List.of(CallState.INITIATED, CallState.RINGING, CallState.ANSWERED, CallState.CONNECTED);
        long liveActiveCalls = callRepository.countByCampaignIdAndStateIn(campaignId, activeStates);

        // 3. Outstanding system-wide call ceiling
        if (liveActiveCalls + requestedCalls > safetyLimits.getMaxOutstandingCalls()) {
            int allowed = (int) Math.max(0, safetyLimits.getMaxOutstandingCalls() - liveActiveCalls);
            if (allowed == 0) {
                SafetyDecision dec = SafetyDecision.rejected(requestedCalls,
                    String.format("Exceeds max outstanding calls limit (%d active / max %d)",
                        liveActiveCalls, safetyLimits.getMaxOutstandingCalls()),
                    liveAvailableAgents, liveRingingCalls, liveActiveCalls);
                recordDecision(dec);
                return dec;
            } else {
                requestedCalls = allowed;
            }
        }

        // 4. Zero available agent safety rule
        if (liveAvailableAgents == 0) {
            SafetyDecision dec = SafetyDecision.rejected(requestedCalls,
                "Strict Safety Enforced: 0 agents currently AVAILABLE in database.",
                0, liveRingingCalls, liveActiveCalls);
            recordDecision(dec);
            return dec;
        }

        // 5. Progressive Mode Enforcement (Strict 1:1 Available Agent Invariant)
        if (mode == DialingMode.PROGRESSIVE) {
            int maxAllowedProgressive = (int) liveAvailableAgents;
            if (requestedCalls > maxAllowedProgressive) {
                SafetyDecision dec = SafetyDecision.reduced(maxAllowedProgressive, requestedCalls,
                    String.format("PROGRESSIVE LIMIT: Capped to %d available agents (requested %d)",
                        maxAllowedProgressive, requestedCalls),
                    liveAvailableAgents, liveRingingCalls, liveActiveCalls);
                recordDecision(dec);
                return dec;
            }

            SafetyDecision dec = SafetyDecision.approved(requestedCalls,
                String.format("PROGRESSIVE APPROVED: %d calls within %d available agents", requestedCalls, liveAvailableAgents),
                liveAvailableAgents, liveRingingCalls, liveActiveCalls);
            recordDecision(dec);
            return dec;
        }

        // 6. Predictive Mode Enforcement & Safety Guardrails
        // Check Ringing Exposure: calls in flight waiting to be answered
        double maxAllowedRinging = liveAvailableAgents * safetyLimits.getMaxRingingExposureRatio();
        if (liveRingingCalls >= maxAllowedRinging) {
            SafetyDecision dec = SafetyDecision.rejected(requestedCalls,
                String.format("RINGING EXPOSURE LIMIT: Currently %d ringing calls (max safe ratio is %.1f with %d agents)",
                    liveRingingCalls, maxAllowedRinging, liveAvailableAgents),
                liveAvailableAgents, liveRingingCalls, liveActiveCalls);
            recordDecision(dec);
            return dec;
        }

        // If provider latency is spiking or health is borderline, fallback to progressive behavior
        if (health.avgLatencyMs() > 600 || health.failureRatePct() > 10.0) {
            int safeProgressive = (int) Math.min(requestedCalls, liveAvailableAgents);
            SafetyDecision dec = SafetyDecision.fallbackProgressive(safeProgressive, requestedCalls,
                String.format("FALLBACK TO PROGRESSIVE: Telecom instability detected (latency: %dms, fail: %.1f%%)",
                    health.avgLatencyMs(), health.failureRatePct()),
                liveAvailableAgents, liveRingingCalls, liveActiveCalls);
            recordDecision(dec);
            return dec;
        }

        // Check if requested predictive calls exceed safe exposure ratio
        int safePredictiveCeiling = (int) Math.ceil(liveAvailableAgents * 2.0);
        if (requestedCalls > safePredictiveCeiling) {
            SafetyDecision dec = SafetyDecision.reduced(safePredictiveCeiling, requestedCalls,
                String.format("PREDICTIVE REDUCED: Capped at safe predictive ceiling %d (requested %d)",
                    safePredictiveCeiling, requestedCalls),
                liveAvailableAgents, liveRingingCalls, liveActiveCalls);
            recordDecision(dec);
            return dec;
        }

        SafetyDecision dec = SafetyDecision.approved(requestedCalls,
            String.format("PREDICTIVE APPROVED: %d calls permitted (available: %d, ringing: %d)",
                requestedCalls, liveAvailableAgents, liveRingingCalls),
            liveAvailableAgents, liveRingingCalls, liveActiveCalls);
        recordDecision(dec);
        return dec;
    }

    private void recordDecision(SafetyDecision decision) {
        if (recentDecisions.size() > 200) {
            recentDecisions.remove(0);
        }
        recentDecisions.add(decision);
        log.info("SAFETY CONTROLLER DECISION: [{}] Approved: {}/{} Reason: {}",
            decision.outcome(), decision.approvedCalls(), decision.requestedCalls(), decision.reason());
    }

    public List<SafetyDecision> getRecentDecisions() {
        return List.copyOf(recentDecisions);
    }

    public void clearDecisions() {
        recentDecisions.clear();
    }
}
