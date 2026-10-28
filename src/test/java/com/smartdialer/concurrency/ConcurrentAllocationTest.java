package com.smartdialer.concurrency;

import com.smartdialer.domain.enums.AgentState;
import com.smartdialer.domain.enums.BorrowerStatus;
import com.smartdialer.domain.model.Agent;
import com.smartdialer.domain.model.Borrower;
import com.smartdialer.repository.AgentRepository;
import com.smartdialer.repository.BorrowerRepository;
import com.smartdialer.statemachine.AgentStateMachine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class ConcurrentAllocationTest {

    @Autowired
    private AgentStateMachine agentStateMachine;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private BorrowerRepository borrowerRepository;

    @Autowired
    private com.smartdialer.dialer.BorrowerQueueService borrowerQueueService;

    @Test
    @DisplayName("Multiple concurrent worker threads racing for the same agent: exactly one must succeed")
    void testConcurrentAgentReservationRace() throws InterruptedException {
        // Given 1 available agent
        Agent agent = new Agent("agent-concurrency-1", "Contended Agent", AgentState.AVAILABLE);
        agent = agentRepository.saveAndFlush(agent);
        Long targetAgentId = agent.getId();

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        AtomicInteger successfulReservations = new AtomicInteger(0);
        AtomicInteger failedReservations = new AtomicInteger(0);

        // When 20 threads simultaneously race to reserve the agent
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGun.await(); // Synchronize all threads to fire simultaneously
                    boolean reserved = agentStateMachine.tryReserveAgent(targetAgentId, 45);
                    if (reserved) {
                        successfulReservations.incrementAndGet();
                    } else {
                        failedReservations.incrementAndGet();
                    }
                } catch (Exception e) {
                    failedReservations.incrementAndGet();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        // Fire race
        startGun.countDown();
        boolean completed = finishLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Then: Exactly 1 thread must win the reservation race
        assertTrue(completed, "All threads should complete within timeout");
        assertEquals(1, successfulReservations.get(), "EXACTLY ONE worker must win the agent reservation");
        assertEquals(threadCount - 1, failedReservations.get(), "All other workers must fail cleanly");

        Agent updatedAgent = agentRepository.findById(targetAgentId).orElseThrow();
        assertEquals(AgentState.RESERVED, updatedAgent.getState());
        assertNotNull(updatedAgent.getReservationExpiresAt());
    }

    @Test
    @DisplayName("Multiple concurrent workers racing for the same borrower: exactly one must succeed")
    void testConcurrentBorrowerReservationRace() throws InterruptedException {
        // Given 1 pending borrower
        Borrower b = new Borrower(100L, "b-race-1", "John Contended", "+15551234567", 1);
        b = borrowerRepository.saveAndFlush(b);
        Long borrowerId = b.getId();
        Long initialVersion = b.getVersion();

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        AtomicInteger successfulClaims = new AtomicInteger(0);
        AtomicInteger rejectedClaims = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGun.await();
                    boolean reserved = borrowerQueueService.tryReserveBorrower(borrowerId, initialVersion);
                    if (reserved) {
                        successfulClaims.incrementAndGet();
                    } else {
                        rejectedClaims.incrementAndGet();
                    }
                } catch (Exception e) {
                    rejectedClaims.incrementAndGet();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startGun.countDown();
        finishLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(1, successfulClaims.get(), "EXACTLY ONE worker can transition borrower from PENDING to RESERVED");
        assertEquals(threadCount - 1, rejectedClaims.get());

        Borrower finalBorrower = borrowerRepository.findById(borrowerId).orElseThrow();
        assertEquals(BorrowerStatus.RESERVED, finalBorrower.getStatus());
    }
}
