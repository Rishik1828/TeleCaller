package com.smartdialer.statemachine;

import com.smartdialer.domain.enums.CallState;
import com.smartdialer.domain.model.Call;
import com.smartdialer.repository.CallRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Centralized Call State Machine.
 * Enforces valid state transitions and safely reconciles out-of-order or duplicate events.
 */
@Component
public class CallStateMachine {

    private static final Logger log = LoggerFactory.getLogger(CallStateMachine.class);

    // Standard valid forward transitions
    private static final Map<CallState, Set<CallState>> VALID_TRANSITIONS = Map.of(
        CallState.QUEUED, Set.of(CallState.RESERVED, CallState.CANCELLED, CallState.FAILED),
        CallState.RESERVED, Set.of(CallState.INITIATED, CallState.CANCELLED, CallState.FAILED),
        CallState.INITIATED, Set.of(CallState.RINGING, CallState.ANSWERED, CallState.CONNECTED, CallState.COMPLETED, CallState.FAILED, CallState.CANCELLED),
        CallState.RINGING, Set.of(CallState.ANSWERED, CallState.CONNECTED, CallState.COMPLETED, CallState.FAILED, CallState.CANCELLED),
        CallState.ANSWERED, Set.of(CallState.CONNECTED, CallState.COMPLETED, CallState.FAILED, CallState.CANCELLED),
        CallState.CONNECTED, Set.of(CallState.COMPLETED, CallState.FAILED, CallState.CANCELLED),
        CallState.COMPLETED, Set.of(),
        CallState.FAILED, Set.of(),
        CallState.CANCELLED, Set.of()
    );

    private final CallRepository callRepository;

    public CallStateMachine(CallRepository callRepository) {
        this.callRepository = callRepository;
    }

    public boolean isValidTransition(CallState current, CallState target) {
        if (current == null || target == null) return false;
        if (current == target) return true; // Idempotent
        if (current.isTerminal()) return false; // Terminal states cannot be altered
        Set<CallState> allowed = VALID_TRANSITIONS.get(current);
        return allowed != null && allowed.contains(target);
    }

    /**
     * Safely transitions call state with event reconciliation for out-of-order telecom events.
     */
    @Transactional
    public TransitionResult transition(Call callInput, CallState targetState, String reason) {
        Call call = (callInput.getId() != null)
            ? callRepository.findById(callInput.getId()).orElse(callInput)
            : callInput;

        CallState current = call.getState();
        Instant now = Instant.now();

        // 1. Idempotent check
        if (current == targetState) {
            log.info("Call {} already in state {}. Duplicate event ignored safely.", call.getCallExternalId(), current);
            return TransitionResult.success(current, targetState, "Idempotent no-op");
        }

        // 2. Terminal protection: If call is already terminal, cannot transition back
        if (current.isTerminal()) {
            String msg = String.format("Call %s is in terminal state %s. Rejecting late transition to %s",
                call.getCallExternalId(), current, targetState);
            log.warn(msg);
            return TransitionResult.failed(msg);
        }

        // 3. Out-of-order event reconciliation
        // Example: COMPLETED arrives while in INITIATED or RINGING (ANSWERED skipped by provider)
        if (targetState == CallState.COMPLETED && (current == CallState.INITIATED || current == CallState.RINGING)) {
            log.warn("Reconciling out-of-order event: COMPLETED received while call {} was in {}. Reconciling timestamps.",
                call.getCallExternalId(), current);
            if (call.getAnsweredAt() == null) {
                call.setAnsweredAt(now);
            }
            if (call.getConnectedAt() == null) {
                call.setConnectedAt(now);
            }
        }

        // 4. Validate transition
        if (!isValidTransition(current, targetState)) {
            String err = String.format("Illegal call transition from %s to %s for call %s",
                current, targetState, call.getCallExternalId());
            log.error(err);
            return TransitionResult.failed(err);
        }

        // 5. Update audit timestamps based on target state
        switch (targetState) {
            case INITIATED -> call.setInitiatedAt(now);
            case RINGING -> {
                if (call.getRingingAt() == null) call.setRingingAt(now);
            }
            case ANSWERED -> {
                if (call.getAnsweredAt() == null) call.setAnsweredAt(now);
            }
            case CONNECTED -> {
                if (call.getConnectedAt() == null) call.setConnectedAt(now);
            }
            case COMPLETED -> {
                call.setEndedAt(now);
                if (callInput.getDurationSeconds() != null && callInput.getDurationSeconds() > 0) {
                    call.setDurationSeconds(callInput.getDurationSeconds());
                } else if (call.getDurationSeconds() == null || call.getDurationSeconds() == 0) {
                    if (call.getConnectedAt() != null) {
                        call.setDurationSeconds(Duration.between(call.getConnectedAt(), now).toSeconds());
                    } else if (call.getAnsweredAt() != null) {
                        call.setDurationSeconds(Duration.between(call.getAnsweredAt(), now).toSeconds());
                    }
                }
            }
            case FAILED -> {
                call.setEndedAt(now);
                call.setFailureReason(reason);
            }
            case CANCELLED -> {
                call.setEndedAt(now);
                call.setFailureReason(reason);
            }
            default -> {}
        }

        call.setState(targetState);
        Call saved = callRepository.saveAndFlush(call);

        // Sync input reference
        callInput.setState(saved.getState());
        callInput.setVersion(saved.getVersion());
        callInput.setInitiatedAt(saved.getInitiatedAt());
        callInput.setRingingAt(saved.getRingingAt());
        callInput.setAnsweredAt(saved.getAnsweredAt());
        callInput.setConnectedAt(saved.getConnectedAt());
        callInput.setEndedAt(saved.getEndedAt());
        callInput.setDurationSeconds(saved.getDurationSeconds());

        log.info("Call {} transitioned {} -> {} [Reason: {}]", call.getCallExternalId(), current, targetState, reason);
        return TransitionResult.success(current, targetState, reason);
    }
}
