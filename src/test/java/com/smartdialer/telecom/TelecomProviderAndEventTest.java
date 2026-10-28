package com.smartdialer.telecom;

import com.smartdialer.domain.enums.AgentState;
import com.smartdialer.domain.enums.CallState;
import com.smartdialer.domain.enums.ProviderType;
import com.smartdialer.domain.model.Agent;
import com.smartdialer.domain.model.Call;
import com.smartdialer.repository.AgentRepository;
import com.smartdialer.repository.CallEventLogRepository;
import com.smartdialer.repository.CallRepository;
import com.smartdialer.telecom.event.ProviderEventProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class TelecomProviderAndEventTest {

    @Autowired
    private ProviderEventProcessor eventProcessor;

    @Autowired
    private CallRepository callRepository;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private CallEventLogRepository eventLogRepository;

    private Call call;
    private Agent agent;

    @BeforeEach
    void setUp() {
        callRepository.deleteAll();
        agentRepository.deleteAll();
        eventLogRepository.deleteAll();

        agent = new Agent("agent-evt-1", "Event Agent", AgentState.DIALING);
        agent = agentRepository.save(agent);

        call = new Call("CALL-EVT-101", 1L, 50L, agent.getId(), ProviderType.MOCK_PROVIDER_A, "IDEM-EVT-101");
        call.setState(CallState.INITIATED);
        call.setProviderCallId("PROV-CALL-101");
        call = callRepository.save(call);
    }

    @Test
    @DisplayName("Duplicate events: Replaying identical providerEventId should be deduplicated safely")
    void testDuplicateEventDeduplication() {
        ProviderEvent evt1 = ProviderEvent.of("EVT-DUP-001", "PROV-CALL-101", "CALL-EVT-101", "ANSWERED", null, null);

        // Process first event
        eventProcessor.onEvent(evt1);

        Call updated = callRepository.findById(call.getId()).orElseThrow();
        assertEquals(CallState.ANSWERED, updated.getState());
        assertTrue(eventLogRepository.existsByProviderEventId("EVT-DUP-001"));

        // Replay duplicate event with same ID
        eventProcessor.onEvent(evt1);

        // State remains ANSWERED without error or duplicate entry
        assertEquals(CallState.ANSWERED, updated.getState());
        assertEquals(1, eventLogRepository.count());
    }

    @Test
    @DisplayName("Out-of-order event: COMPLETED arriving while INITIATED should reconcile call safely")
    void testOutOfOrderEventHandling() {
        ProviderEvent oofEvent = ProviderEvent.of("EVT-OOF-001", "PROV-CALL-101", "CALL-EVT-101", "COMPLETED", null, 45L);

        eventProcessor.onEvent(oofEvent);

        Call completedCall = callRepository.findById(call.getId()).orElseThrow();
        assertEquals(CallState.COMPLETED, completedCall.getState());
        assertNotNull(completedCall.getAnsweredAt(), "Answered timestamp must be backfilled");
        assertNotNull(completedCall.getEndedAt());
        assertEquals(45L, completedCall.getDurationSeconds());

        // Agent should be freed back to AVAILABLE
        Agent updatedAgent = agentRepository.findById(agent.getId()).orElseThrow();
        assertEquals(AgentState.AVAILABLE, updatedAgent.getState());
    }
}
