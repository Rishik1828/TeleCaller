package com.smartdialer.dialer;

import com.smartdialer.domain.enums.*;
import com.smartdialer.domain.model.Agent;
import com.smartdialer.domain.model.Borrower;
import com.smartdialer.domain.model.Call;
import com.smartdialer.domain.model.CallIntent;
import com.smartdialer.repository.AgentRepository;
import com.smartdialer.repository.BorrowerRepository;
import com.smartdialer.repository.CallIntentRepository;
import com.smartdialer.repository.CallRepository;
import com.smartdialer.safety.SafetyLimits;
import com.smartdialer.statemachine.AgentStateMachine;
import com.smartdialer.statemachine.CallStateMachine;
import com.smartdialer.telecom.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Call Allocator:
 * Executes atomic reservations for borrowers and agents, persists call intents,
 * and interacts with the Telecom Provider.
 */
@Service
public class CallAllocator {

    private static final Logger log = LoggerFactory.getLogger(CallAllocator.class);

    private final BorrowerRepository borrowerRepository;
    private final BorrowerQueueService borrowerQueueService;
    private final AgentRepository agentRepository;
    private final CallRepository callRepository;
    private final CallIntentRepository callIntentRepository;
    private final AgentStateMachine agentStateMachine;
    private final CallStateMachine callStateMachine;
    private final TelecomProviderRegistry providerRegistry;
    private final SafetyLimits safetyLimits;

    public CallAllocator(BorrowerRepository borrowerRepository,
                         BorrowerQueueService borrowerQueueService,
                         AgentRepository agentRepository,
                         CallRepository callRepository,
                         CallIntentRepository callIntentRepository,
                         AgentStateMachine agentStateMachine,
                         CallStateMachine callStateMachine,
                         TelecomProviderRegistry providerRegistry,
                         SafetyLimits safetyLimits) {
        this.borrowerRepository = borrowerRepository;
        this.borrowerQueueService = borrowerQueueService;
        this.agentRepository = agentRepository;
        this.callRepository = callRepository;
        this.callIntentRepository = callIntentRepository;
        this.agentStateMachine = agentStateMachine;
        this.callStateMachine = callStateMachine;
        this.providerRegistry = providerRegistry;
        this.safetyLimits = safetyLimits;
    }

    /**
     * Allocates approved calls for a campaign.
     */
    public AllocationResult allocateAndInitiate(Long campaignId, DialingMode mode, int approvedCalls, ProviderType providerType) {
        if (approvedCalls <= 0) {
            return new AllocationResult(0, 0, 0, "No calls approved by safety controller");
        }

        int initiatedCount = 0;
        int failedCount = 0;

        for (int i = 0; i < approvedCalls; i++) {
            boolean success = allocateSingleCall(campaignId, mode, providerType);
            if (success) {
                initiatedCount++;
            } else {
                failedCount++;
                // If we ran out of eligible borrowers or agents, break early
                break;
            }
        }

        String summary = String.format("Allocated %d/%d calls for campaign %d (failures/exhausted: %d)",
            initiatedCount, approvedCalls, campaignId, failedCount);
        log.info(summary);

        return new AllocationResult(approvedCalls, initiatedCount, failedCount, summary);
    }

    /**
     * Atomically claims an eligible borrower and an available agent, then initiates the call.
     */
    public boolean allocateSingleCall(Long campaignId, DialingMode mode, ProviderType providerType) {
        // 1. Atomically reserve next eligible borrower
        Borrower reservedBorrower = claimEligibleBorrower(campaignId);
        if (reservedBorrower == null) {
            log.info("No eligible borrowers found for campaign {}", campaignId);
            return false;
        }

        // 2. Reserve Agent
        // Progressive mode MUST have an agent reserved prior to dialing.
        // Predictive mode also reserves an available agent if available.
        Agent reservedAgent = claimAvailableAgent();
        if (reservedAgent == null && mode == DialingMode.PROGRESSIVE) {
            // Rollback borrower reservation
            releaseBorrower(reservedBorrower, "No available agents for progressive dialing");
            return false;
        }

        Long agentId = reservedAgent != null ? reservedAgent.getId() : null;
        String callExternalId = "CALL-" + UUID.randomUUID();
        String idempotencyKey = "IDEM-" + UUID.randomUUID();

        // 3. Phase 1: Persist Call Intent and initial Call record BEFORE external call
        Call call = createAndPersistCallIntent(
            callExternalId, idempotencyKey, campaignId, reservedBorrower.getId(), agentId, providerType
        );

        if (call == null) {
            // Intent persistence failed
            releaseBorrower(reservedBorrower, "Intent creation failed");
            if (reservedAgent != null) {
                agentStateMachine.transition(reservedAgent.getId(), AgentState.AVAILABLE, "Intent creation failed");
            }
            return false;
        }

        // 4. Update agent to DIALING if agent assigned
        if (reservedAgent != null) {
            agentStateMachine.transition(reservedAgent.getId(), AgentState.DIALING, "Initiating call " + callExternalId);
            agentRepository.findById(reservedAgent.getId()).ifPresent(freshAgent -> {
                freshAgent.setCurrentCallId(call.getId());
                agentRepository.save(freshAgent);
            });
        }

        // 5. Phase 2: Call Telecom Provider
        TelecomProvider provider = providerRegistry.getProvider(providerType);
        CallInitiateRequest req = new CallInitiateRequest(
            callExternalId,
            idempotencyKey,
            reservedBorrower.getPhoneNumber(),
            campaignId,
            reservedBorrower.getId(),
            agentId
        );

        CallInitiateResponse resp;
        try {
            resp = provider.initiateCall(req);
        } catch (Exception ex) {
            log.error("Provider exception initiating call " + callExternalId, ex);
            resp = CallInitiateResponse.rejected("EXCEPTION: " + ex.getMessage(), 0);
        }

        // 6. Handle provider dispatch result
        if (resp.accepted()) {
            finalizeCallAccepted(call, resp.providerCallId(), idempotencyKey);
            return true;
        } else {
            finalizeCallRejected(call, reservedBorrower, reservedAgent, resp.failureReason());
            return false;
        }
    }

    private Borrower claimEligibleBorrower(Long campaignId) {
        return borrowerQueueService.claimNextEligibleBorrower(campaignId);
    }

    private Agent claimAvailableAgent() {
        List<Agent> candidates = agentRepository.findAvailableAgents(PageRequest.of(0, 10));
        for (Agent a : candidates) {
            boolean reserved = agentStateMachine.tryReserveAgent(
                a.getId(), safetyLimits.getAgentReservationLeaseSeconds()
            );
            if (reserved) {
                return agentRepository.findById(a.getId()).orElse(a);
            }
        }
        return null;
    }

    private void releaseBorrower(Borrower borrower, String reason) {
        Borrower managed = borrowerRepository.findById(borrower.getId()).orElse(borrower);
        managed.setStatus(BorrowerStatus.PENDING);
        managed.setLastOutcome(reason);
        borrowerRepository.save(managed);
    }

    @Transactional
    public Call createAndPersistCallIntent(String callExternalId, String idempotencyKey,
                                          Long campaignId, Long borrowerId, Long agentId,
                                          ProviderType providerType) {
        try {
            CallIntent intent = new CallIntent(callExternalId, idempotencyKey, campaignId, borrowerId, agentId, providerType);
            callIntentRepository.save(intent);

            Call call = new Call(callExternalId, campaignId, borrowerId, agentId, providerType, idempotencyKey);
            call.setState(CallState.RESERVED);
            call.setLeaseExpiresAt(Instant.now().plusSeconds(safetyLimits.getCallTimeoutSeconds()));
            return callRepository.save(call);
        } catch (Exception e) {
            log.error("Failed to persist call intent for {}", callExternalId, e);
            return null;
        }
    }

    @Transactional
    public void finalizeCallAccepted(Call call, String providerCallId, String idempotencyKey) {
        call.setProviderCallId(providerCallId);
        call.setState(CallState.INITIATED);
        call.setInitiatedAt(Instant.now());
        callRepository.save(call);

        callIntentRepository.findByIdempotencyKey(idempotencyKey).ifPresent(intent -> {
            intent.setDispatched(true);
            intent.setConfirmedAt(Instant.now());
            callIntentRepository.save(intent);
        });

        log.info("Call {} dispatched successfully to provider (providerCallId: {})", call.getCallExternalId(), providerCallId);
    }

    @Transactional
    public void finalizeCallRejected(Call call, Borrower borrower, Agent agent, String failureReason) {
        call.setState(CallState.FAILED);
        call.setFailureReason(failureReason);
        call.setEndedAt(Instant.now());
        callRepository.save(call);

        if (agent != null) {
            agentStateMachine.transition(agent.getId(), AgentState.AVAILABLE, "Call initiation rejected by provider: " + failureReason);
        }

        if (borrower != null) {
            Borrower managedBorrower = borrowerRepository.findById(borrower.getId()).orElse(borrower);
            managedBorrower.incrementAttempts(failureReason);
            managedBorrower.setStatus(BorrowerStatus.RETRY_SCHEDULED);
            managedBorrower.setNextRetryAt(Instant.now().plusSeconds(30));
            borrowerRepository.save(managedBorrower);
        }

        log.warn("Call {} initiation rejected by provider: {}", call.getCallExternalId(), failureReason);
    }
}
