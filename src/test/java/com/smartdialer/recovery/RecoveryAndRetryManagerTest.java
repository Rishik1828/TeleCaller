package com.smartdialer.recovery;

import com.smartdialer.domain.enums.AgentState;
import com.smartdialer.domain.enums.BorrowerStatus;
import com.smartdialer.domain.enums.CallState;
import com.smartdialer.domain.enums.ProviderType;
import com.smartdialer.domain.model.Agent;
import com.smartdialer.domain.model.Borrower;
import com.smartdialer.domain.model.Call;
import com.smartdialer.domain.model.CallIntent;
import com.smartdialer.repository.AgentRepository;
import com.smartdialer.repository.BorrowerRepository;
import com.smartdialer.repository.CallIntentRepository;
import com.smartdialer.repository.CallRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class RecoveryAndRetryManagerTest {

    @Autowired
    private RecoveryAndRetryManager recoveryManager;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private BorrowerRepository borrowerRepository;

    @Autowired
    private CallRepository callRepository;

    @Autowired
    private CallIntentRepository callIntentRepository;

    @BeforeEach
    void setUp() {
        callIntentRepository.deleteAll();
        callRepository.deleteAll();
        borrowerRepository.deleteAll();
        agentRepository.deleteAll();
    }

    @Test
    @DisplayName("Worker Crash Recovery: Reconciles undispatched call intents and frees reserved resources")
    void testWorkerCrashRecovery() {
        // 1. Simulate worker crash state:
        // Agent reserved
        Agent agent = new Agent("agent-crash-1", "Crash Agent", AgentState.RESERVED);
        agent.setReservationExpiresAt(Instant.now().plusSeconds(60));
        agent = agentRepository.save(agent);

        // Borrower reserved
        Borrower b = new Borrower(1L, "b-crash-1", "Crash Borrower", "+15559990000", 1);
        b.setStatus(BorrowerStatus.RESERVED);
        b = borrowerRepository.save(b);

        // Call reserved
        Call call = new Call("CALL-CRASH-1", 1L, b.getId(), agent.getId(), ProviderType.MOCK_PROVIDER_A, "IDEM-CRASH-1");
        call.setState(CallState.RESERVED);
        callRepository.save(call);

        // CallIntent created 30s ago but dispatched=false (worker died before HTTP call)
        CallIntent intent = new CallIntent("CALL-CRASH-1", "IDEM-CRASH-1", 1L, b.getId(), agent.getId(), ProviderType.MOCK_PROVIDER_A);
        intent.setDispatched(false);
        intent.setCreatedAt(Instant.now().minusSeconds(30));
        callIntentRepository.save(intent);

        // 2. Run recovery cycle
        RecoveryAndRetryManager.RecoverySummary summary = recoveryManager.runPeriodicRecovery();

        assertTrue(summary.orphanedIntentsReconciled() >= 1);

        // 3. Verify clean state restoration
        Agent recoveredAgent = agentRepository.findById(agent.getId()).orElseThrow();
        assertEquals(AgentState.AVAILABLE, recoveredAgent.getState(), "Agent should be restored to AVAILABLE");

        Borrower recoveredBorrower = borrowerRepository.findById(b.getId()).orElseThrow();
        assertEquals(BorrowerStatus.PENDING, recoveredBorrower.getStatus(), "Borrower should be reset to PENDING");

        Call recoveredCall = callRepository.findByCallExternalId("CALL-CRASH-1").orElseThrow();
        assertEquals(CallState.FAILED, recoveredCall.getState(), "Orphaned call marked FAILED");
    }

    @Test
    @DisplayName("Reservation Lease Expiration: Agents held in RESERVED past lease duration are auto-freed")
    void testExpiredAgentReservationCleanup() {
        Agent agent = new Agent("agent-lease-1", "Lease Expired Agent", AgentState.RESERVED);
        // Expired 10 seconds ago
        agent.setReservationExpiresAt(Instant.now().minusSeconds(10));
        agent = agentRepository.save(agent);

        RecoveryAndRetryManager.RecoverySummary summary = recoveryManager.runPeriodicRecovery();

        assertTrue(summary.expiredAgentReservationsRecovered() >= 1);
        Agent freed = agentRepository.findById(agent.getId()).orElseThrow();
        assertEquals(AgentState.AVAILABLE, freed.getState());
        assertNull(freed.getReservationExpiresAt());
    }
}
