package com.smartdialer.statemachine;

import com.smartdialer.domain.enums.AgentState;
import com.smartdialer.domain.model.Agent;
import com.smartdialer.repository.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Enforces the formal Agent State Machine and guarantees atomic state transitions.
 * Prevents concurrent workers from reserving or corrupting the same agent.
 */
@Component
public class AgentStateMachine {

    private static final Logger log = LoggerFactory.getLogger(AgentStateMachine.class);

    // Valid state transitions: Source -> Allowed Targets
    private static final Map<AgentState, Set<AgentState>> VALID_TRANSITIONS = Map.of(
        AgentState.OFFLINE, Set.of(AgentState.AVAILABLE, AgentState.PAUSED),
        AgentState.AVAILABLE, Set.of(AgentState.RESERVED, AgentState.PAUSED, AgentState.OFFLINE),
        AgentState.RESERVED, Set.of(AgentState.DIALING, AgentState.AVAILABLE, AgentState.OFFLINE, AgentState.PAUSED),
        AgentState.DIALING, Set.of(AgentState.CONNECTED, AgentState.WRAP_UP, AgentState.AVAILABLE, AgentState.OFFLINE),
        AgentState.CONNECTED, Set.of(AgentState.WRAP_UP, AgentState.OFFLINE),
        AgentState.WRAP_UP, Set.of(AgentState.AVAILABLE, AgentState.PAUSED, AgentState.OFFLINE),
        AgentState.PAUSED, Set.of(AgentState.AVAILABLE, AgentState.OFFLINE)
    );

    private final AgentRepository agentRepository;

    public AgentStateMachine(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    public boolean isValidTransition(AgentState current, AgentState target) {
        if (current == null || target == null) return false;
        if (current == target) return true; // Idempotent no-op
        Set<AgentState> allowed = VALID_TRANSITIONS.get(current);
        return allowed != null && allowed.contains(target);
    }

    /**
     * Atomically reserve an agent with a lease duration.
     * Guarantees that only ONE worker wins the reservation race.
     */
    @Transactional
    public boolean tryReserveAgent(Long agentId, long leaseSeconds) {
        Agent agent = agentRepository.findById(agentId).orElse(null);
        if (agent == null) {
            log.warn("Cannot reserve non-existent agent {}", agentId);
            return false;
        }

        if (agent.getState() != AgentState.AVAILABLE) {
            log.debug("Agent {} cannot be reserved; state is {}", agentId, agent.getState());
            return false;
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(leaseSeconds);

        int updated = agentRepository.atomicTransition(
            agentId,
            AgentState.AVAILABLE,
            AgentState.RESERVED,
            agent.getVersion(),
            expiresAt,
            now
        );

        if (updated == 1) {
            log.info("Agent {} successfully RESERVED by atomic CAS (lease expires at {})", agentId, expiresAt);
            return true;
        } else {
            log.info("Atomic reservation failed for agent {} (lost race to concurrent worker)", agentId);
            return false;
        }
    }

    /**
     * Perform an explicit, verified state transition.
     */
    @Transactional
    public TransitionResult transition(Long agentId, AgentState targetState, String reason) {
        Agent agent = agentRepository.findById(agentId).orElse(null);
        if (agent == null) {
            return TransitionResult.failed("Agent not found: " + agentId);
        }

        AgentState currentState = agent.getState();
        if (currentState == targetState) {
            return TransitionResult.success(currentState, targetState, "Already in target state (idempotent)");
        }

        if (!isValidTransition(currentState, targetState)) {
            String err = String.format("Illegal agent transition from %s to %s for agent %s. Reason: %s",
                currentState, targetState, agentId, reason);
            log.error(err);
            return TransitionResult.failed(err);
        }

        agent.setState(targetState);
        if (targetState == AgentState.AVAILABLE) {
            agent.setReservationExpiresAt(null);
            agent.setCurrentCallId(null);
        }
        agentRepository.save(agent);

        log.info("Agent {} transitioned {} -> {} [Reason: {}]", agentId, currentState, targetState, reason);
        return TransitionResult.success(currentState, targetState, reason);
    }
}
