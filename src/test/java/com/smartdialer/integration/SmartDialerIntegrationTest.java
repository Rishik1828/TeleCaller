package com.smartdialer.integration;

import com.smartdialer.dialer.ProgressiveDialer;
import com.smartdialer.domain.enums.AgentState;
import com.smartdialer.domain.enums.DialingMode;
import com.smartdialer.domain.enums.ProviderType;
import com.smartdialer.domain.model.Agent;
import com.smartdialer.domain.model.Borrower;
import com.smartdialer.domain.model.Campaign;
import com.smartdialer.repository.AgentRepository;
import com.smartdialer.repository.BorrowerRepository;
import com.smartdialer.repository.CallRepository;
import com.smartdialer.repository.CampaignRepository;
import com.smartdialer.safety.SafetyDecision;
import com.smartdialer.simulation.SimulationConfig;
import com.smartdialer.simulation.SimulationMetrics;
import com.smartdialer.simulation.SimulationScenario;
import com.smartdialer.simulation.SimulationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class SmartDialerIntegrationTest {

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private BorrowerRepository borrowerRepository;

    @Autowired
    private CallRepository callRepository;

    @Autowired
    private ProgressiveDialer progressiveDialer;

    @Autowired
    private SimulationService simulationService;

    @BeforeEach
    void setUp() {
        callRepository.deleteAll();
        borrowerRepository.deleteAll();
        agentRepository.deleteAll();
        campaignRepository.deleteAll();
    }

    @Test
    @DisplayName("End-to-End Progressive Dialing: Never initiates more calls than available agents")
    void testEndToEndProgressiveDialing() {
        // 1. Create Progressive Campaign
        Campaign campaign = new Campaign("Collections Progressive Q4", DialingMode.PROGRESSIVE, ProviderType.MOCK_PROVIDER_A);
        campaign = campaignRepository.save(campaign);

        // 2. Seed 3 available agents
        for (int i = 1; i <= 3; i++) {
            agentRepository.save(new Agent("prog-agent-" + i, "Agent " + i, AgentState.AVAILABLE));
        }

        // 3. Seed 10 borrowers
        for (int i = 1; i <= 10; i++) {
            borrowerRepository.save(new Borrower(campaign.getId(), "borrower-" + i, "Customer " + i, "+1555123400" + i, 1));
        }

        // 4. Run Dialing Cycle
        ProgressiveDialer.DialingCycleReport report = progressiveDialer.executeDialingCycle(campaign.getId());

        assertNotNull(report);
        assertEquals(DialingMode.PROGRESSIVE, report.mode());
        assertEquals(3, report.pacingDecision().proposedCalls());
        assertEquals(SafetyDecision.Outcome.APPROVED, report.safetyDecision().outcome());
        assertEquals(3, report.safetyDecision().approvedCalls());
        assertEquals(3, report.allocationResult().successfullyInitiated());

        // Verify exactly 3 calls were initiated and recorded
        assertEquals(3, callRepository.count());
    }

    @Test
    @DisplayName("Run Full Simulation Scenario B: Verifies metrics, answer tracking, and zero call abandonment")
    void testSimulationScenarioBExecution() {
        SimulationConfig config = new SimulationConfig(
            SimulationScenario.SCENARIO_B,
            DialingMode.PREDICTIVE,
            ProviderType.MOCK_PROVIDER_A,
            15,  // 15 agents
            50,  // 50 borrowers
            5,   // 5 cycles
            150  // 150ms delay
        );

        SimulationMetrics metrics = simulationService.runSimulation(config);

        assertNotNull(metrics);
        assertTrue(metrics.totalCallsInitiated() > 0, "Simulation should initiate calls");
        assertTrue(metrics.safetyApprovals() > 0 || metrics.safetyReductions() > 0,
            "Safety controller should actively approve or safely reduce pacing requests");
        assertEquals(0, metrics.abandonedCalls(), "Safety invariant: Zero abandoned connected calls");
        assertNotNull(metrics.executionSummary());
    }
}
