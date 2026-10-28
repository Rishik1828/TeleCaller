package com.smartdialer.statemachine;

import com.smartdialer.domain.enums.CallState;
import com.smartdialer.domain.enums.ProviderType;
import com.smartdialer.domain.model.Call;
import com.smartdialer.repository.CallRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class CallStateMachineTest {

    @Autowired
    private CallStateMachine stateMachine;

    @Autowired
    private CallRepository callRepository;

    private Call call;

    @BeforeEach
    void setUp() {
        callRepository.deleteAll();
        call = new Call("CALL-TEST-100", 1L, 10L, 20L, ProviderType.MOCK_PROVIDER_A, "IDEM-100");
        call = callRepository.save(call);
    }

    @Test
    @DisplayName("Should successfully execute forward call state lifecycle")
    void testStandardCallLifecycle() {
        // QUEUED -> RESERVED
        TransitionResult r1 = stateMachine.transition(call, CallState.RESERVED, "Reserved agent and borrower");
        assertTrue(r1.successful());

        // RESERVED -> INITIATED
        TransitionResult r2 = stateMachine.transition(call, CallState.INITIATED, "Provider accepted");
        assertTrue(r2.successful());
        assertNotNull(call.getInitiatedAt());

        // INITIATED -> RINGING
        TransitionResult r3 = stateMachine.transition(call, CallState.RINGING, "Telecom ringing");
        assertTrue(r3.successful());
        assertNotNull(call.getRingingAt());

        // RINGING -> ANSWERED
        TransitionResult r4 = stateMachine.transition(call, CallState.ANSWERED, "Customer answered");
        assertTrue(r4.successful());
        assertNotNull(call.getAnsweredAt());

        // ANSWERED -> CONNECTED
        TransitionResult r5 = stateMachine.transition(call, CallState.CONNECTED, "Audio bridge established");
        assertTrue(r5.successful());
        assertNotNull(call.getConnectedAt());

        // CONNECTED -> COMPLETED
        TransitionResult r6 = stateMachine.transition(call, CallState.COMPLETED, "Customer hung up");
        assertTrue(r6.successful());
        assertNotNull(call.getEndedAt());
        assertEquals(CallState.COMPLETED, call.getState());
    }

    @Test
    @DisplayName("Should protect terminal states from subsequent modification")
    void testTerminalStateProtection() {
        stateMachine.transition(call, CallState.RESERVED, "Setup");
        stateMachine.transition(call, CallState.INITIATED, "Init");
        stateMachine.transition(call, CallState.COMPLETED, "Call completed early");

        assertEquals(CallState.COMPLETED, call.getState());

        // Attempting to transition completed call to ANSWERED should fail
        TransitionResult lateAnswer = stateMachine.transition(call, CallState.ANSWERED, "Late event");
        assertFalse(lateAnswer.successful());
        assertTrue(lateAnswer.message().contains("terminal state"));
        assertEquals(CallState.COMPLETED, call.getState());
    }

    @Test
    @DisplayName("Should reconcile out-of-order COMPLETED event arriving before ANSWERED")
    void testOutOfOrderCompletedReconciliation() {
        stateMachine.transition(call, CallState.RESERVED, "Setup");
        stateMachine.transition(call, CallState.INITIATED, "Init");

        // Telecom sends COMPLETED directly, skipping ANSWERED
        TransitionResult oof = stateMachine.transition(call, CallState.COMPLETED, "Out of order complete");
        assertTrue(oof.successful());
        assertEquals(CallState.COMPLETED, call.getState());
        assertNotNull(call.getAnsweredAt(), "Answered timestamp should be reconciled");
        assertNotNull(call.getConnectedAt(), "Connected timestamp should be reconciled");
        assertNotNull(call.getEndedAt());
    }

    @Test
    @DisplayName("Should handle duplicate events idempotently without throwing error")
    void testIdempotentEventHandling() {
        stateMachine.transition(call, CallState.RESERVED, "Setup");
        stateMachine.transition(call, CallState.INITIATED, "Init");
        stateMachine.transition(call, CallState.RINGING, "Ringing 1");

        // Duplicate RINGING
        TransitionResult dup = stateMachine.transition(call, CallState.RINGING, "Ringing 2");
        assertTrue(dup.successful());
        assertTrue(dup.message().contains("Idempotent no-op"));
    }
}
