package com.smartdialer.safety;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "smartdialer.safety")
public class SafetyLimits {

    private double maxCallsPerAvailableAgent = 1.0;
    private double maxRingingExposureRatio = 1.5;
    private int maxOutstandingCalls = 500;
    private double circuitBreakerFailureThresholdPct = 25.0;
    private int minSampleSizeForPredictive = 20;
    private double defaultAnswerRate = 0.30;
    private long agentReservationLeaseSeconds = 45;
    private long callTimeoutSeconds = 60;

    public double getMaxCallsPerAvailableAgent() { return maxCallsPerAvailableAgent; }
    public void setMaxCallsPerAvailableAgent(double maxCallsPerAvailableAgent) { this.maxCallsPerAvailableAgent = maxCallsPerAvailableAgent; }

    public double getMaxRingingExposureRatio() { return maxRingingExposureRatio; }
    public void setMaxRingingExposureRatio(double maxRingingExposureRatio) { this.maxRingingExposureRatio = maxRingingExposureRatio; }

    public int getMaxOutstandingCalls() { return maxOutstandingCalls; }
    public void setMaxOutstandingCalls(int maxOutstandingCalls) { this.maxOutstandingCalls = maxOutstandingCalls; }

    public double getCircuitBreakerFailureThresholdPct() { return circuitBreakerFailureThresholdPct; }
    public void setCircuitBreakerFailureThresholdPct(double threshold) { this.circuitBreakerFailureThresholdPct = threshold; }

    public int getMinSampleSizeForPredictive() { return minSampleSizeForPredictive; }
    public void setMinSampleSizeForPredictive(int minSampleSizeForPredictive) { this.minSampleSizeForPredictive = minSampleSizeForPredictive; }

    public double getDefaultAnswerRate() { return defaultAnswerRate; }
    public void setDefaultAnswerRate(double defaultAnswerRate) { this.defaultAnswerRate = defaultAnswerRate; }

    public long getAgentReservationLeaseSeconds() { return agentReservationLeaseSeconds; }
    public void setAgentReservationLeaseSeconds(long agentReservationLeaseSeconds) { this.agentReservationLeaseSeconds = agentReservationLeaseSeconds; }

    public long getCallTimeoutSeconds() { return callTimeoutSeconds; }
    public void setCallTimeoutSeconds(long callTimeoutSeconds) { this.callTimeoutSeconds = callTimeoutSeconds; }
}
