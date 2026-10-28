package com.smartdialer.dialer;

import com.smartdialer.domain.enums.AgentState;
import com.smartdialer.domain.enums.CallState;
import com.smartdialer.domain.enums.DialingMode;
import com.smartdialer.domain.model.Campaign;
import com.smartdialer.pacing.PacingDecision;
import com.smartdialer.pacing.PacingInputs;
import com.smartdialer.pacing.PredictivePacingEngine;
import com.smartdialer.repository.AgentRepository;
import com.smartdialer.repository.CallRepository;
import com.smartdialer.repository.CampaignRepository;
import com.smartdialer.safety.SafetyController;
import com.smartdialer.safety.SafetyDecision;
import com.smartdialer.safety.SafetyLimits;
import com.smartdialer.telecom.ProviderHealth;
import com.smartdialer.telecom.TelecomProvider;
import com.smartdialer.telecom.TelecomProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProgressiveDialer {

    private static final Logger log = LoggerFactory.getLogger(ProgressiveDialer.class);

    private final CampaignRepository campaignRepository;
    private final AgentRepository agentRepository;
    private final CallRepository callRepository;
    private final PredictivePacingEngine pacingEngine;
    private final SafetyController safetyController;
    private final CallAllocator callAllocator;
    private final TelecomProviderRegistry providerRegistry;
    private final SafetyLimits safetyLimits;

    public record DialingCycleReport(
        Long campaignId,
        DialingMode mode,
        PacingDecision pacingDecision,
        SafetyDecision safetyDecision,
        AllocationResult allocationResult
    ) {}

    public ProgressiveDialer(CampaignRepository campaignRepository,
                             AgentRepository agentRepository,
                             CallRepository callRepository,
                             PredictivePacingEngine pacingEngine,
                             SafetyController safetyController,
                             CallAllocator callAllocator,
                             TelecomProviderRegistry providerRegistry,
                             SafetyLimits safetyLimits) {
        this.campaignRepository = campaignRepository;
        this.agentRepository = agentRepository;
        this.callRepository = callRepository;
        this.pacingEngine = pacingEngine;
        this.safetyController = safetyController;
        this.callAllocator = callAllocator;
        this.providerRegistry = providerRegistry;
        this.safetyLimits = safetyLimits;
    }

    /**
     * Executes one complete dialing iteration:
     * Campaign -> Pacing Engine -> Safety Controller -> Call Allocator -> Telecom Provider
     */
    public DialingCycleReport executeDialingCycle(Long campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId)
            .orElseThrow(() -> new IllegalArgumentException("Campaign not found: " + campaignId));

        long availableAgents = agentRepository.countByState(AgentState.AVAILABLE);
        long dialingAgents = agentRepository.countByState(AgentState.DIALING);
        long connectedCalls = callRepository.countByCampaignIdAndState(campaignId, CallState.CONNECTED);
        long ringingCalls = callRepository.countByCampaignIdAndState(campaignId, CallState.RINGING);
        long totalAnswered = callRepository.countAnsweredCalls(campaignId);
        long totalFinished = callRepository.countFinishedAttempts(campaignId);

        Double avgDuration = callRepository.findAverageTalkDurationSeconds(campaignId);
        double avgTalkDuration = (avgDuration != null && avgDuration > 0) ? avgDuration : 90.0;

        TelecomProvider provider = providerRegistry.getProvider(campaign.getActiveProvider());
        ProviderHealth health = provider.getProviderHealth();

        // 1. Pacing Calculation
        PacingDecision pacingDecision;
        if (campaign.getDialingMode() == DialingMode.PROGRESSIVE) {
            // Progressive rule: strictly 1 call per available agent
            int progressiveProposed = (int) availableAgents;
            String reason = String.format("Progressive 1:1 mode. Proposing %d calls for %d available agents",
                progressiveProposed, progressiveProposed);
            pacingDecision = PacingDecision.of(
                campaignId, progressiveProposed, campaign.getTargetAnswerRate(),
                availableAgents, 1.0, connectedCalls + ringingCalls, availableAgents, reason, false
            );
        } else {
            // Predictive rule
            PacingInputs inputs = new PacingInputs(
                campaignId,
                availableAgents,
                dialingAgents,
                connectedCalls,
                ringingCalls,
                campaign.getTargetAnswerRate(),
                safetyLimits.getMinSampleSizeForPredictive(),
                totalFinished,
                totalAnswered,
                avgTalkDuration,
                15.0, // average call setup time in seconds
                health.failureRatePct(),
                health.avgLatencyMs()
            );
            pacingDecision = pacingEngine.calculateProposedCalls(inputs);
        }

        // 2. Safety Controller Gatekeeper
        SafetyDecision safetyDecision = safetyController.evaluate(
            campaignId,
            campaign.getDialingMode(),
            pacingDecision.proposedCalls(),
            campaign.getActiveProvider()
        );

        // 3. Call Allocator Execution
        AllocationResult allocationResult = callAllocator.allocateAndInitiate(
            campaignId,
            campaign.getDialingMode(),
            safetyDecision.approvedCalls(),
            campaign.getActiveProvider()
        );

        return new DialingCycleReport(
            campaignId,
            campaign.getDialingMode(),
            pacingDecision,
            safetyDecision,
            allocationResult
        );
    }
}
