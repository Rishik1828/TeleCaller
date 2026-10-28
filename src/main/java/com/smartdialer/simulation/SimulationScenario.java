package com.smartdialer.simulation;

public enum SimulationScenario {
    SCENARIO_A("Scenario A: Low Answer Rate (20%), 120s Talk Time"),
    SCENARIO_B("Scenario B: Medium Answer Rate (50%), 90s Talk Time"),
    SCENARIO_C("Scenario C: High Answer Rate (70%), 180s Talk Time"),
    SCENARIO_D("Scenario D: Dynamic Changing Conditions (Variable Answer Rate, Latency & Failures)");

    private final String description;

    SimulationScenario(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
