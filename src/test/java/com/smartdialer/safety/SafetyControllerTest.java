package com.smartdialer.safety;

import com.smartdialer.domain.enums.AgentState;
import com.smartdialer.domain.enums.CallState;
import com.smartdialer.domain.enums.DialingMode;
import com.smartdialer.domain.enums.ProviderType;
import com.smartdialer.domain.model.Agent;
import com.smartdialer.domain.model.Call;
import com.smartdialer.repository.AgentRepository;
import com.smartdialer.repository.CallRepository;
import com.smartdialer.telecom.MockProviderA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class SafetyControllerTest {

    @Autowired
    private SafetyController safetyController;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private CallRepository callRepository;

    @Autowired
    private MockProviderA mockProviderA;

    @BeforeEach
    void setUp() {
        agentRepository.deleteAll();
        callRepository.deleteAll();
        mockProviderA.resetMetrics();
        mockProviderA.setSimulatedFailureRate(1.0);
        mockProviderA.setSimulatedLatencyMs(50);
    }

    @Test
    @DisplayName("Zero available agents: Safety Controller must strictly REJECT call requests")
    void testZeroAvailableAgentsRejection() {
        // Given 0 available agents
        SafetyDecision decision = safetyController.evaluate(
            1L, DialingMode.PROGRESSIVE, 5, ProviderType.MOCK_PROVIDER_A
        );

        assertEquals(SafetyDecision.Outcome.REJECTED, decision.outcome());
        assertEquals(0, decision.approvedCalls());
        assertTrue(decision.reason().contains("0 agents currently AVAILABLE"));
    }

    @Test
    @DisplayName("Progressive mode: Safety Controller must cap requested calls to available agents count")
    void testProgressiveStrictCap() {
        // Seed 4 available agents
        for (int i = 1; i <= 4; i++) {
            agentRepository.save(new Agent("agent-" + i, "Agent " + i, AgentState.AVAILABLE));
        }

        // Pacing engine asks for 10 calls
        SafetyDecision decision = safetyController.evaluate(
            1L, DialingMode.PROGRESSIVE, 10, ProviderType.MOCK_PROVIDER_A
        );

        // Safety Controller must REDUCE to 4
        assertEquals(SafetyDecision.Outcome.REDUCED, decision.outcome());
        assertEquals(4, decision.approvedCalls());
        assertEquals(10, decision.requestedCalls());
        assertTrue(decision.reason().contains("PROGRESSIVE LIMIT"));
    }

    @Test
    @DisplayName("Ringing exposure check: Reject pacing if too many unanswered calls are in flight")
    void testRingingExposureRejection() {
        // Seed 2 available agents
        agentRepository.save(new Agent("agent-1", "Agent 1", AgentState.AVAILABLE));
        agentRepository.save(new Agent("agent-2", "Agent 2", AgentState.AVAILABLE));

        // Seed 6 ringing calls (ratio = 6/2 = 3.0 > maxRingingExposureRatio 1.5)
        for (int i = 1; i <= 6; i++) {
            Call c = new Call("CALL-RING-" + i, 1L, (long) i, null, ProviderType.MOCK_PROVIDER_A, "IDEM-RING-" + i);
            c.setState(CallState.RINGING);
            callRepository.save(c);
        }

        SafetyDecision decision = safetyController.evaluate(
            1L, DialingMode.PREDICTIVE, 5, ProviderType.MOCK_PROVIDER_A
        );

        assertEquals(SafetyDecision.Outcome.REJECTED, decision.outcome());
        assertEquals(0, decision.approvedCalls());
        assertTrue(decision.reason().contains("RINGING EXPOSURE LIMIT"));
    }

    @Test
    @DisplayName("Fail-Closed Circuit Breaker: Provider outage trips circuit breaker and rejects calls")
    void testCircuitBreakerOpenOnProviderOutage() {
        // Seed 5 available agents
        for (int i = 1; i <= 5; i++) {
            agentRepository.save(new Agent("agent-" + i, "Agent " + i, AgentState.AVAILABLE));
        }

        // Simulate provider outage: failure rate 40% (exceeds threshold 25%)
        mockProviderA.setSimulatedFailureRate(40.0);

        SafetyDecision decision = safetyController.evaluate(
            1L, DialingMode.PREDICTIVE, 5, ProviderType.MOCK_PROVIDER_A
        );

        assertEquals(SafetyDecision.Outcome.REJECTED, decision.outcome());
        assertEquals(0, decision.approvedCalls());
        assertTrue(decision.reason().contains("CIRCUIT-BREAKER OPEN"));
    }
}
