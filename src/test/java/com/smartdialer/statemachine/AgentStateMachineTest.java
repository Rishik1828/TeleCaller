package com.smartdialer.statemachine;

import com.smartdialer.domain.enums.AgentState;
import com.smartdialer.domain.model.Agent;
import com.smartdialer.repository.AgentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class AgentStateMachineTest {

    @Autowired
    private AgentStateMachine stateMachine;

    @Autowired
    private AgentRepository agentRepository;

    private Agent agent;

    @BeforeEach
    void setUp() {
        agentRepository.deleteAll();
        agent = new Agent("agent-test-1", "Test Agent", AgentState.AVAILABLE);
        agent = agentRepository.save(agent);
    }

    @Test
    @DisplayName("Should successfully transition through standard agent lifecycle")
    void testStandardLifecycleTransitions() {
        // AVAILABLE -> RESERVED
        boolean reserved = stateMachine.tryReserveAgent(agent.getId(), 30);
        assertTrue(reserved);

        Agent a = agentRepository.findById(agent.getId()).orElseThrow();
        assertEquals(AgentState.RESERVED, a.getState());
        assertNotNull(a.getReservationExpiresAt());

        // RESERVED -> DIALING
        TransitionResult r1 = stateMachine.transition(a.getId(), AgentState.DIALING, "Dialing initiated");
        assertTrue(r1.successful());

        // DIALING -> CONNECTED
        TransitionResult r2 = stateMachine.transition(a.getId(), AgentState.CONNECTED, "Borrower answered");
        assertTrue(r2.successful());

        // CONNECTED -> WRAP_UP
        TransitionResult r3 = stateMachine.transition(a.getId(), AgentState.WRAP_UP, "Call ended");
        assertTrue(r3.successful());

        // WRAP_UP -> AVAILABLE
        TransitionResult r4 = stateMachine.transition(a.getId(), AgentState.AVAILABLE, "Ready for next call");
        assertTrue(r4.successful());

        Agent finalAgent = agentRepository.findById(agent.getId()).orElseThrow();
        assertEquals(AgentState.AVAILABLE, finalAgent.getState());
    }

    @Test
    @DisplayName("Should reject illegal state transitions")
    void testRejectIllegalTransitions() {
        // AVAILABLE -> CONNECTED directly is illegal without RESERVED/DIALING
        TransitionResult res = stateMachine.transition(agent.getId(), AgentState.CONNECTED, "Illegal jump");
        assertFalse(res.successful());
        assertTrue(res.message().contains("Illegal agent transition"));

        // State remains AVAILABLE
        Agent current = agentRepository.findById(agent.getId()).orElseThrow();
        assertEquals(AgentState.AVAILABLE, current.getState());
    }

    @Test
    @DisplayName("Should allow pausing or disconnecting from any active state")
    void testDisconnectOrPauseFromActive() {
        // AVAILABLE -> PAUSED
        TransitionResult r1 = stateMachine.transition(agent.getId(), AgentState.PAUSED, "Agent took lunch break");
        assertTrue(r1.successful());

        // PAUSED -> OFFLINE
        TransitionResult r2 = stateMachine.transition(agent.getId(), AgentState.OFFLINE, "End of shift");
        assertTrue(r2.successful());
    }
}
