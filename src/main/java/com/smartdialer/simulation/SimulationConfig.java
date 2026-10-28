package com.smartdialer.simulation;

import com.smartdialer.domain.enums.DialingMode;
import com.smartdialer.domain.enums.ProviderType;

public record SimulationConfig(
    SimulationScenario scenario,
    DialingMode dialingMode,
    ProviderType providerType,
    int agentCount,
    int borrowerCount,
    int cyclesToRun,
    int cycleDelayMs
) {
    public static SimulationConfig defaultForScenario(SimulationScenario scenario, DialingMode mode) {
        return new SimulationConfig(
            scenario,
            mode != null ? mode : DialingMode.PROGRESSIVE,
            ProviderType.MOCK_PROVIDER_A,
            25,
            100,
            10,
            500
        );
    }
}
