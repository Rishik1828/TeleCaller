package com.smartdialer.telecom.event;

import com.smartdialer.domain.enums.AgentState;
import com.smartdialer.domain.enums.BorrowerStatus;
import com.smartdialer.domain.enums.CallState;
import com.smartdialer.domain.model.Agent;
import com.smartdialer.domain.model.Borrower;
import com.smartdialer.domain.model.Call;
import com.smartdialer.domain.model.CallEventLog;
import com.smartdialer.repository.AgentRepository;
import com.smartdialer.repository.BorrowerRepository;
import com.smartdialer.repository.CallEventLogRepository;
import com.smartdialer.repository.CallRepository;
import com.smartdialer.statemachine.AgentStateMachine;
import com.smartdialer.statemachine.CallStateMachine;
import com.smartdialer.telecom.ProviderEvent;
import com.smartdialer.telecom.ProviderEventListener;
import com.smartdialer.telecom.TelecomProvider;
import com.smartdialer.telecom.TelecomProviderRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
public class ProviderEventProcessor implements ProviderEventListener {

    private static final Logger log = LoggerFactory.getLogger(ProviderEventProcessor.class);

    private final CallEventLogRepository eventLogRepository;
    private final CallRepository callRepository;
    private final AgentRepository agentRepository;
    private final BorrowerRepository borrowerRepository;
    private final CallStateMachine callStateMachine;
    private final AgentStateMachine agentStateMachine;
    private final TelecomProviderRegistry providerRegistry;

    public ProviderEventProcessor(CallEventLogRepository eventLogRepository,
                                  CallRepository callRepository,
                                  AgentRepository agentRepository,
                                  BorrowerRepository borrowerRepository,
                                  CallStateMachine callStateMachine,
                                  AgentStateMachine agentStateMachine,
                                  TelecomProviderRegistry providerRegistry) {
        this.eventLogRepository = eventLogRepository;
        this.callRepository = callRepository;
        this.agentRepository = agentRepository;
        this.borrowerRepository = borrowerRepository;
        this.callStateMachine = callStateMachine;
        this.agentStateMachine = agentStateMachine;
        this.providerRegistry = providerRegistry;
    }

    @PostConstruct
    public void registerToAllProviders() {
        for (TelecomProvider provider : providerRegistry.getAllProviders().values()) {
            provider.registerEventListener(this);
            log.info("Registered ProviderEventProcessor to provider {}", provider.getProviderType());
        }
    }

    @Override
    @Transactional
    public synchronized void onEvent(ProviderEvent event) {
        log.info("Processing provider event: ID={} type={} callExternalId={} providerCallId={}",
            event.providerEventId(), event.eventType(), event.callExternalId(), event.providerCallId());

        // 1. Persistent Deduplication check
        if (eventLogRepository.existsByProviderEventId(event.providerEventId())) {
            log.warn("Duplicate provider event detected: {}. Safely dropping.", event.providerEventId());
            return;
        }

        // 2. Persist event log
        CallEventLog eventLog = new CallEventLog(
            event.providerEventId(),
            event.providerCallId(),
            event.callExternalId(),
            event.eventType(),
            event.rawPayload()
        );

        // 3. Find target call record
        Optional<Call> callOpt = findCall(event);
        if (callOpt.isEmpty()) {
            eventLog.setApplied(false);
            eventLog.setProcessingNote("Call not found for externalId: " + event.callExternalId() + ", provId: " + event.providerCallId());
            eventLogRepository.save(eventLog);
            log.warn(eventLog.getProcessingNote());
            return;
        }

        Call call = callOpt.get();

        // 4. Dispatch based on event type
        try {
            switch (event.eventType().toUpperCase()) {
                case "INITIATED" -> handleInitiated(call, event);
                case "RINGING" -> handleRinging(call, event);
                case "ANSWERED" -> handleAnswered(call, event);
                case "CONNECTED" -> handleConnected(call, event);
                case "COMPLETED" -> handleCompleted(call, event);
                case "FAILED" -> handleFailed(call, event);
                case "CANCELLED" -> handleCancelled(call, event);
                default -> log.warn("Unrecognized event type: {}", event.eventType());
            }

            eventLog.setApplied(true);
            eventLog.setProcessingNote("Successfully applied to call " + call.getCallExternalId());
        } catch (Exception ex) {
            log.error("Error processing event " + event.providerEventId(), ex);
            eventLog.setApplied(false);
            eventLog.setProcessingNote("Exception: " + ex.getMessage());
        }

        eventLogRepository.save(eventLog);
    }

    private Optional<Call> findCall(ProviderEvent event) {
        if (event.callExternalId() != null && !event.callExternalId().isBlank()) {
            Optional<Call> c = callRepository.findByCallExternalId(event.callExternalId());
            if (c.isPresent()) return c;
        }
        if (event.providerCallId() != null && !event.providerCallId().isBlank()) {
            return callRepository.findByProviderCallId(event.providerCallId());
        }
        return Optional.empty();
    }

    private void handleInitiated(Call call, ProviderEvent event) {
        if (call.getProviderCallId() == null && event.providerCallId() != null) {
            call.setProviderCallId(event.providerCallId());
        }
        callStateMachine.transition(call, CallState.INITIATED, "Provider event: INITIATED");
    }

    private void handleRinging(Call call, ProviderEvent event) {
        callStateMachine.transition(call, CallState.RINGING, "Provider event: RINGING");
    }

    private void handleAnswered(Call call, ProviderEvent event) {
        callStateMachine.transition(call, CallState.ANSWERED, "Provider event: ANSWERED");

        // Safety verification: Ensure agent is attached
        if (call.getAgentId() != null) {
            agentStateMachine.transition(call.getAgentId(), AgentState.CONNECTED, "Call answered by borrower");
        } else {
            // Predictive pacing without dedicated agent reservation: check if any agent available
            // If not available, this is an ABANDONED call!
            log.error("CRITICAL: Borrower answered call {} but no agent was pre-assigned!", call.getCallExternalId());
            call.setAbandoned(true);
        }
    }

    private void handleConnected(Call call, ProviderEvent event) {
        callStateMachine.transition(call, CallState.CONNECTED, "Provider event: CONNECTED");
        if (call.getAgentId() != null) {
            Agent agent = agentRepository.findById(call.getAgentId()).orElse(null);
            if (agent != null && agent.getState() != AgentState.CONNECTED) {
                agentStateMachine.transition(agent.getId(), AgentState.CONNECTED, "Call connected");
            }
        }
    }

    private void handleCompleted(Call call, ProviderEvent event) {
        if (event.durationSeconds() != null && event.durationSeconds() > 0) {
            call.setDurationSeconds(event.durationSeconds());
        }
        callStateMachine.transition(call, CallState.COMPLETED, "Provider event: COMPLETED");

        // Release agent to WRAP_UP and then AVAILABLE
        if (call.getAgentId() != null) {
            Agent agent = agentRepository.findById(call.getAgentId()).orElse(null);
            if (agent != null) {
                long duration = call.getDurationSeconds() != null ? call.getDurationSeconds() : 0L;
                agent.setTotalTalkSeconds(agent.getTotalTalkSeconds() + duration);
                agent.setTotalCallsHandled(agent.getTotalCallsHandled() + 1);
                agentRepository.save(agent);

                agentStateMachine.transition(agent.getId(), AgentState.WRAP_UP, "Call completed, post-call wrap-up");
                // Immediately transition to AVAILABLE for high-throughput prototype
                agentStateMachine.transition(agent.getId(), AgentState.AVAILABLE, "Wrap-up finished, agent ready");
            }
        }

        // Complete borrower
        if (call.getBorrowerId() != null) {
            borrowerRepository.findById(call.getBorrowerId()).ifPresent(b -> {
                b.setStatus(BorrowerStatus.COMPLETED);
                b.setLastOutcome("CALL_COMPLETED");
                borrowerRepository.save(b);
            });
        }
    }

    private void handleFailed(Call call, ProviderEvent event) {
        callStateMachine.transition(call, CallState.FAILED, "Provider event: " + event.failureReason());

        // Free agent back to AVAILABLE if assigned
        if (call.getAgentId() != null) {
            agentStateMachine.transition(call.getAgentId(), AgentState.AVAILABLE, "Call failed: " + event.failureReason());
        }

        // Apply borrower retry logic
        if (call.getBorrowerId() != null) {
            borrowerRepository.findById(call.getBorrowerId()).ifPresent(borrower -> {
                borrower.incrementAttempts(event.failureReason());
                if (borrower.getAttempts() < borrower.getMaxAttempts()) {
                    borrower.setStatus(BorrowerStatus.RETRY_SCHEDULED);
                    // Exponential backoff: 30s * 2^attempts
                    long delaySec = 30L * (1L << Math.min(borrower.getAttempts(), 5));
                    borrower.setNextRetryAt(Instant.now().plusSeconds(delaySec));
                    log.info("Borrower {} scheduled for retry in {} seconds (attempt {}/{})",
                        borrower.getId(), delaySec, borrower.getAttempts(), borrower.getMaxAttempts());
                } else {
                    borrower.setStatus(BorrowerStatus.FAILED);
                    log.info("Borrower {} reached max attempts {}. Marked FAILED.", borrower.getId(), borrower.getMaxAttempts());
                }
                borrowerRepository.save(borrower);
            });
        }
    }

    private void handleCancelled(Call call, ProviderEvent event) {
        callStateMachine.transition(call, CallState.CANCELLED, "Provider event: CANCELLED (" + event.failureReason() + ")");
        if (call.getAgentId() != null) {
            agentStateMachine.transition(call.getAgentId(), AgentState.AVAILABLE, "Call cancelled");
        }
        if (call.getBorrowerId() != null) {
            borrowerRepository.findById(call.getBorrowerId()).ifPresent(b -> {
                b.setStatus(BorrowerStatus.PENDING);
                b.setLastOutcome("CANCELLED_RESET_PENDING");
                borrowerRepository.save(b);
            });
        }
    }
}
