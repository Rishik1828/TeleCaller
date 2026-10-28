package com.smartdialer.simulation;

import com.smartdialer.dialer.ProgressiveDialer;
import com.smartdialer.domain.enums.AgentState;
import com.smartdialer.domain.enums.BorrowerStatus;
import com.smartdialer.domain.enums.CallState;
import com.smartdialer.domain.enums.CampaignState;
import com.smartdialer.domain.enums.DialingMode;
import com.smartdialer.domain.model.Agent;
import com.smartdialer.domain.model.Borrower;
import com.smartdialer.domain.model.Call;
import com.smartdialer.domain.model.Campaign;
import com.smartdialer.pacing.PacingDecision;
import com.smartdialer.repository.AgentRepository;
import com.smartdialer.repository.BorrowerRepository;
import com.smartdialer.repository.CallRepository;
import com.smartdialer.repository.CampaignRepository;
import com.smartdialer.safety.SafetyController;
import com.smartdialer.safety.SafetyDecision;
import com.smartdialer.telecom.ProviderHealth;
import com.smartdialer.telecom.TelecomProvider;
import com.smartdialer.telecom.TelecomProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class SimulationService {

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    private final CampaignRepository campaignRepository;
    private final AgentRepository agentRepository;
    private final BorrowerRepository borrowerRepository;
    private final CallRepository callRepository;
    private final ProgressiveDialer progressiveDialer;
    private final SafetyController safetyController;
    private final TelecomProviderRegistry providerRegistry;

    private final AtomicLong simulationIdCounter = new AtomicLong(1000);
    private final Map<Long, SimulationMetrics> simulationHistory = new ConcurrentHashMap<>();

    public SimulationService(CampaignRepository campaignRepository,
                             AgentRepository agentRepository,
                             BorrowerRepository borrowerRepository,
                             CallRepository callRepository,
                             ProgressiveDialer progressiveDialer,
                             SafetyController safetyController,
                             TelecomProviderRegistry providerRegistry) {
        this.campaignRepository = campaignRepository;
        this.agentRepository = agentRepository;
        this.borrowerRepository = borrowerRepository;
        this.callRepository = callRepository;
        this.progressiveDialer = progressiveDialer;
        this.safetyController = safetyController;
        this.providerRegistry = providerRegistry;
    }

    /**
     * Executes a full reproducible simulation run according to scenario parameters.
     */
    public SimulationMetrics runSimulation(SimulationConfig config) {
        long simId = simulationIdCounter.incrementAndGet();
        Instant startTime = Instant.now();
        log.info("Starting Simulation {} with scenario: {}, mode: {}", simId, config.scenario(), config.dialingMode());

        safetyController.clearDecisions();

        // 1. Initialize or reset provider parameters
        TelecomProvider provider = providerRegistry.getProvider(config.providerType());
        provider.resetMetrics();
        configureProviderForScenario(provider, config.scenario());

        // 2. Create isolated simulation campaign
        Campaign campaign = new Campaign(
            "Sim-" + simId + "-" + config.scenario().name(),
            config.dialingMode(),
            config.providerType()
        );
        campaign.setState(CampaignState.RUNNING);
        campaign = campaignRepository.save(campaign);

        // 3. Seed Agents and Borrowers
        seedSimulationData(campaign.getId(), config.agentCount(), config.borrowerCount());

        // 4. Run dialing cycles
        PacingDecision lastPacing = null;
        SafetyDecision lastSafety = null;

        int safetyApprovals = 0;
        int safetyReductions = 0;
        int safetyRejections = 0;

        for (int cycle = 1; cycle <= config.cyclesToRun(); cycle++) {
            // In Scenario D: dynamic fluctuation across cycles
            if (config.scenario() == SimulationScenario.SCENARIO_D) {
                applyDynamicFluctuation(provider, cycle);
            }

            ProgressiveDialer.DialingCycleReport report = progressiveDialer.executeDialingCycle(campaign.getId());
            lastPacing = report.pacingDecision();
            lastSafety = report.safetyDecision();

            if (lastSafety != null) {
                switch (lastSafety.outcome()) {
                    case APPROVED -> safetyApprovals++;
                    case REDUCED -> safetyReductions++;
                    case REJECTED -> safetyRejections++;
                    case FALLBACK_PROGRESSIVE -> safetyReductions++;
                }
            }

            try {
                Thread.sleep(Math.max(50, config.cycleDelayMs()));
            } catch (InterruptedException ignored) {}
        }

        // Wait brief moment for async telephony events to settle
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ignored) {}

        Instant endTime = Instant.now();

        // 5. Calculate Metrics
        SimulationMetrics metrics = compileMetrics(
            simId,
            campaign.getId(),
            config,
            startTime,
            endTime,
            lastPacing,
            lastSafety,
            provider.getProviderHealth(),
            safetyApprovals,
            safetyReductions,
            safetyRejections
        );

        simulationHistory.put(simId, metrics);
        log.info("Simulation {} finished. Initiated: {}, Answered: {}, Abandoned: {}, Agent Utilization: {}%",
            simId, metrics.totalCallsInitiated(), metrics.totalCallsAnswered(),
            metrics.abandonedCalls(), metrics.agentUtilizationPct());

        return metrics;
    }

    private void configureProviderForScenario(TelecomProvider provider, SimulationScenario scenario) {
        switch (scenario) {
            case SCENARIO_A -> {
                // Scenario A: 20% answer rate, 120-second average talk time
                provider.setSimulatedAnswerRate(0.20);
                provider.setSimulatedTalkDurationSeconds(120);
                provider.setSimulatedFailureRate(2.0);
                provider.setSimulatedLatencyMs(60);
            }
            case SCENARIO_B -> {
                // Scenario B: 50% answer rate, 90-second average talk time
                provider.setSimulatedAnswerRate(0.50);
                provider.setSimulatedTalkDurationSeconds(90);
                provider.setSimulatedFailureRate(3.0);
                provider.setSimulatedLatencyMs(80);
            }
            case SCENARIO_C -> {
                // Scenario C: 70% answer rate, 180-second average talk time
                provider.setSimulatedAnswerRate(0.70);
                provider.setSimulatedTalkDurationSeconds(180);
                provider.setSimulatedFailureRate(2.0);
                provider.setSimulatedLatencyMs(90);
            }
            case SCENARIO_D -> {
                // Scenario D: Initial baseline (will fluctuate dynamically during run)
                provider.setSimulatedAnswerRate(0.60);
                provider.setSimulatedTalkDurationSeconds(60);
                provider.setSimulatedFailureRate(5.0);
                provider.setSimulatedLatencyMs(100);
            }
        }
    }

    private void applyDynamicFluctuation(TelecomProvider provider, int cycle) {
        if (cycle == 3) {
            // Sudden answer rate drop from 60% down to 15%
            log.info("Scenario D Shock: Answer rate collapsing to 15%");
            provider.setSimulatedAnswerRate(0.15);
        } else if (cycle == 5) {
            // Provider network latency and failure rate spike
            log.info("Scenario D Shock: Provider latency spiking to 700ms, failures to 25%");
            provider.setSimulatedLatencyMs(700);
            provider.setSimulatedFailureRate(25.0);
        } else if (cycle == 7) {
            // Recovery
            log.info("Scenario D Recovery: Provider normalizing, answer rate back to 40%");
            provider.setSimulatedLatencyMs(80);
            provider.setSimulatedFailureRate(3.0);
            provider.setSimulatedAnswerRate(0.40);
        }
    }

    private void seedSimulationData(Long campaignId, int agentCount, int borrowerCount) {
        // Reset or seed available agents
        for (int i = 1; i <= agentCount; i++) {
            String extId = "agent-" + i;
            Agent agent = agentRepository.findByAgentExternalId(extId).orElse(null);
            if (agent == null) {
                agent = new Agent(extId, "Agent " + i, AgentState.AVAILABLE);
            } else {
                agent.setState(AgentState.AVAILABLE);
                agent.setCurrentCallId(null);
                agent.setReservationExpiresAt(null);
            }
            agentRepository.save(agent);
        }

        // Seed pending borrowers
        for (int i = 1; i <= borrowerCount; i++) {
            String bExtId = "b-" + campaignId + "-" + i;
            Borrower b = new Borrower(
                campaignId,
                bExtId,
                "Borrower " + i,
                "+1555" + String.format("%07d", i),
                (i % 3) + 1 // priorities 1 to 3
            );
            borrowerRepository.save(b);
        }
    }

    private SimulationMetrics compileMetrics(Long simId, Long campaignId, SimulationConfig config,
                                            Instant start, Instant end,
                                            PacingDecision lastPacing, SafetyDecision lastSafety,
                                            ProviderHealth health,
                                            int approvals, int reductions, int rejections) {
        List<Call> calls = callRepository.findByCampaignIdAndStateIn(campaignId, List.of(
            CallState.INITIATED, CallState.RINGING, CallState.ANSWERED,
            CallState.CONNECTED, CallState.COMPLETED, CallState.FAILED, CallState.CANCELLED
        ));

        long initiated = calls.size();
        long answered = calls.stream().filter(c -> c.getAnsweredAt() != null || c.getState() == CallState.ANSWERED || c.getState() == CallState.CONNECTED || c.getState() == CallState.COMPLETED).count();
        long connected = calls.stream().filter(c -> c.getConnectedAt() != null || c.getState() == CallState.CONNECTED || c.getState() == CallState.COMPLETED).count();
        long completed = calls.stream().filter(c -> c.getState() == CallState.COMPLETED).count();
        long failed = calls.stream().filter(c -> c.getState() == CallState.FAILED).count();
        long cancelled = calls.stream().filter(c -> c.getState() == CallState.CANCELLED).count();
        long abandoned = calls.stream().filter(Call::isAbandoned).count();

        long totalDurationSec = Math.max(1, Duration.between(start, end).toSeconds());
        List<Agent> agents = agentRepository.findAll();
        long totalTalkSec = agents.stream().mapToLong(Agent::getTotalTalkSeconds).sum();
        long maxPossibleAgentSec = agents.size() * totalDurationSec;

        double utilization = maxPossibleAgentSec > 0
            ? Math.min(100.0, Math.round(((double) totalTalkSec / maxPossibleAgentSec) * 10000.0) / 100.0)
            : 0.0;
        long totalIdleSec = Math.max(0, maxPossibleAgentSec - totalTalkSec);

        String summary = String.format(
            "Scenario: %s [%s] | Calls: %d init, %d ans, %d conn, %d comp, %d fail, %d abandoned | Safety: %d appr, %d red, %d rej | Utilization: %.2f%%",
            config.scenario().name(), config.dialingMode(),
            initiated, answered, connected, completed, failed, abandoned,
            approvals, reductions, rejections, utilization
        );

        return new SimulationMetrics(
            simId,
            config.scenario().getDescription(),
            config.dialingMode(),
            config.providerType().name(),
            config.agentCount(),
            config.borrowerCount(),
            initiated,
            answered,
            connected,
            completed,
            failed,
            cancelled,
            abandoned,
            utilization,
            totalTalkSec,
            totalIdleSec,
            lastPacing,
            lastSafety,
            health,
            approvals,
            reductions,
            rejections,
            start,
            end,
            summary
        );
    }

    public Optional<SimulationMetrics> getSimulationResult(Long simulationId) {
        return Optional.ofNullable(simulationHistory.get(simulationId));
    }

    public List<SimulationMetrics> getAllSimulationResults() {
        return new ArrayList<>(simulationHistory.values());
    }
}
