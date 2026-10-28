package com.smartdialer.api.dto;

import com.smartdialer.domain.enums.AgentState;
import com.smartdialer.domain.enums.DialingMode;
import com.smartdialer.domain.enums.ProviderType;
import com.smartdialer.simulation.SimulationScenario;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class ApiDtos {

    public record CreateCampaignRequest(
        @NotBlank(message = "Campaign name is required")
        String name,
        DialingMode dialingMode,
        ProviderType activeProvider,
        Double targetAnswerRate
    ) {}

    public record CreateAgentRequest(
        @NotBlank(message = "agentExternalId is required")
        String agentExternalId,
        @NotBlank(message = "Agent name is required")
        String name,
        AgentState state
    ) {}

    public record UpdateAgentStatusRequest(
        @NotNull(message = "State is required")
        AgentState state,
        String reason
    ) {}

    public record CreateBorrowerRequest(
        @NotBlank(message = "borrowerExternalId is required")
        String borrowerExternalId,
        @NotBlank(message = "Name is required")
        String name,
        @NotBlank(message = "Phone number is required")
        String phoneNumber,
        Integer priority
    ) {}

    public record ProviderEventRequest(
        @NotBlank(message = "providerEventId is required")
        String providerEventId,
        String providerCallId,
        String callExternalId,
        @NotBlank(message = "eventType is required")
        String eventType,
        String failureReason,
        Long durationSeconds
    ) {}

    public record SimulationRunRequest(
        SimulationScenario scenario,
        DialingMode dialingMode,
        ProviderType providerType,
        Integer agentCount,
        Integer borrowerCount,
        Integer cyclesToRun,
        Integer cycleDelayMs
    ) {}
}
