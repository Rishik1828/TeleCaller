package com.smartdialer.api;

import com.smartdialer.api.dto.ApiDtos.SimulationRunRequest;
import com.smartdialer.domain.enums.DialingMode;
import com.smartdialer.domain.enums.ProviderType;
import com.smartdialer.simulation.SimulationConfig;
import com.smartdialer.simulation.SimulationMetrics;
import com.smartdialer.simulation.SimulationScenario;
import com.smartdialer.simulation.SimulationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/simulation")
public class SimulationController {

    private final SimulationService simulationService;

    public SimulationController(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @PostMapping("/run")
    public ResponseEntity<SimulationMetrics> runSimulation(@RequestBody(required = false) SimulationRunRequest req) {
        SimulationScenario scenario = (req != null && req.scenario() != null)
            ? req.scenario()
            : SimulationScenario.SCENARIO_B;

        DialingMode mode = (req != null && req.dialingMode() != null)
            ? req.dialingMode()
            : DialingMode.PREDICTIVE;

        ProviderType providerType = (req != null && req.providerType() != null)
            ? req.providerType()
            : ProviderType.MOCK_PROVIDER_A;

        int agents = (req != null && req.agentCount() != null) ? req.agentCount() : 25;
        int borrowers = (req != null && req.borrowerCount() != null) ? req.borrowerCount() : 100;
        int cycles = (req != null && req.cyclesToRun() != null) ? req.cyclesToRun() : 10;
        int delay = (req != null && req.cycleDelayMs() != null) ? req.cycleDelayMs() : 300;

        SimulationConfig config = new SimulationConfig(
            scenario, mode, providerType, agents, borrowers, cycles, delay
        );

        SimulationMetrics metrics = simulationService.runSimulation(config);
        return ResponseEntity.ok(metrics);
    }

    @GetMapping("/{id}/results")
    public ResponseEntity<SimulationMetrics> getResult(@PathVariable Long id) {
        return simulationService.getSimulationResult(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/results")
    public ResponseEntity<List<SimulationMetrics>> getAllResults() {
        return ResponseEntity.ok(simulationService.getAllSimulationResults());
    }
}
