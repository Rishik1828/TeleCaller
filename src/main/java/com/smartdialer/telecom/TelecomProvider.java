package com.smartdialer.telecom;

import com.smartdialer.domain.enums.ProviderType;

public interface TelecomProvider {

    ProviderType getProviderType();

    CallInitiateResponse initiateCall(CallInitiateRequest request);

    boolean cancelCall(String providerCallId);

    ProviderHealth getProviderHealth();

    void registerEventListener(ProviderEventListener listener);

    void resetMetrics();

    // Simulation hooks
    void setSimulatedFailureRate(double failureRatePct);
    void setSimulatedLatencyMs(long latencyMs);
    void setSimulatedAnswerRate(double answerRatePct);
    void setSimulatedTalkDurationSeconds(long talkDurationSeconds);
}
