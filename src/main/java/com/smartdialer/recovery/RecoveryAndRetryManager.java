package com.smartdialer.recovery;

import com.smartdialer.domain.enums.AgentState;
import com.smartdialer.domain.enums.BorrowerStatus;
import com.smartdialer.domain.enums.CallState;
import com.smartdialer.domain.model.Agent;
import com.smartdialer.domain.model.Borrower;
import com.smartdialer.domain.model.Call;
import com.smartdialer.domain.model.CallIntent;
import com.smartdialer.repository.AgentRepository;
import com.smartdialer.repository.BorrowerRepository;
import com.smartdialer.repository.CallIntentRepository;
import com.smartdialer.repository.CallRepository;
import com.smartdialer.statemachine.AgentStateMachine;
import com.smartdialer.statemachine.CallStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class RecoveryAndRetryManager {

    private static final Logger log = LoggerFactory.getLogger(RecoveryAndRetryManager.class);

    private final AgentRepository agentRepository;
    private final BorrowerRepository borrowerRepository;
    private final CallRepository callRepository;
    private final CallIntentRepository callIntentRepository;
    private final AgentStateMachine agentStateMachine;
    private final CallStateMachine callStateMachine;

    public record RecoverySummary(
        int expiredAgentReservationsRecovered,
        int orphanedIntentsReconciled,
        int staleCallsTimedOut
    ) {}

    public RecoveryAndRetryManager(AgentRepository agentRepository,
                                  BorrowerRepository borrowerRepository,
                                  CallRepository callRepository,
                                  CallIntentRepository callIntentRepository,
                                  AgentStateMachine agentStateMachine,
                                  CallStateMachine callStateMachine) {
        this.agentRepository = agentRepository;
        this.borrowerRepository = borrowerRepository;
        this.callRepository = callRepository;
        this.callIntentRepository = callIntentRepository;
        this.agentStateMachine = agentStateMachine;
        this.callStateMachine = callStateMachine;
    }

    /**
     * Periodic background reconciliation loop.
     * Recovers expired reservations, handles worker crash leftovers, and cleans up dangling state.
     */
    @Scheduled(fixedDelayString = "${smartdialer.recovery.interval-ms:5000}")
    @Transactional
    public RecoverySummary runPeriodicRecovery() {
        Instant now = Instant.now();

        // 1. Recover expired agent reservations
        List<Agent> expiredAgents = agentRepository.findExpiredReservations(now);
        int agentsRecovered = 0;
        for (Agent a : expiredAgents) {
            log.warn("Recovering expired reservation for agent {} (expired at {})", a.getAgentExternalId(), a.getReservationExpiresAt());
            agentStateMachine.transition(a.getId(), AgentState.AVAILABLE, "RECOVERY: Reservation lease expired without active call connection");
            agentsRecovered++;
        }

        // 2. Recover undispatched call intents (e.g. worker crashed between DB intent and HTTP provider call)
        Instant intentThreshold = now.minusSeconds(15);
        List<CallIntent> orphanedIntents = callIntentRepository.findByDispatchedFalseAndCreatedAtBefore(intentThreshold);
        int intentsReconciled = 0;
        for (CallIntent intent : orphanedIntents) {
            log.warn("Recovering orphaned call intent {} for borrower {}", intent.getCallExternalId(), intent.getBorrowerId());
            // Check call record
            callRepository.findByCallExternalId(intent.getCallExternalId()).ifPresent(call -> {
                callStateMachine.transition(call, CallState.FAILED, "RECOVERY: Worker crashed prior to telecom dispatch");
            });

            // Free borrower
            borrowerRepository.findById(intent.getBorrowerId()).ifPresent(b -> {
                if (b.getStatus() == BorrowerStatus.RESERVED) {
                    b.setStatus(BorrowerStatus.PENDING);
                    b.setLastOutcome("CRASH_RECOVERY_RESET");
                    borrowerRepository.save(b);
                }
            });

            // Free agent
            if (intent.getAgentId() != null) {
                agentStateMachine.transition(intent.getAgentId(), AgentState.AVAILABLE, "CRASH_RECOVERY: Undispatched intent cleared");
            }

            intent.setDispatched(true); // mark handled
            callIntentRepository.save(intent);
            intentsReconciled++;
        }

        // 3. Stale in-flight calls that exceeded timeout without terminal event
        List<Call> staleCalls = callRepository.findStaleActiveCalls(now);
        int staleCount = 0;
        for (Call call : staleCalls) {
            log.warn("Call {} exceeded lease timeout. Reconciling to FAILED.", call.getCallExternalId());
            callStateMachine.transition(call, CallState.FAILED, "TIMEOUT: In-flight lease expired");
            if (call.getAgentId() != null) {
                agentStateMachine.transition(call.getAgentId(), AgentState.AVAILABLE, "TIMEOUT: Call lease expired");
            }
            if (call.getBorrowerId() != null) {
                borrowerRepository.findById(call.getBorrowerId()).ifPresent(b -> {
                    b.incrementAttempts("CALL_TIMEOUT");
                    b.setStatus(BorrowerStatus.RETRY_SCHEDULED);
                    b.setNextRetryAt(Instant.now().plusSeconds(60));
                    borrowerRepository.save(b);
                });
            }
            staleCount++;
        }

        if (agentsRecovered > 0 || intentsReconciled > 0 || staleCount > 0) {
            log.info("Recovery cycle completed: agentsRecovered={}, intentsReconciled={}, staleCallsTimedOut={}",
                agentsRecovered, intentsReconciled, staleCount);
        }

        return new RecoverySummary(agentsRecovered, intentsReconciled, staleCount);
    }
}
