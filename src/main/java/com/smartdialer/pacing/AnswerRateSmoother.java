package com.smartdialer.pacing;

import org.springframework.stereotype.Component;

/**
 * Bayesian m-estimate answer rate smoother.
 * Prevents erratic pacing swings when starting up with few calls or small sample sizes.
 */
@Component
public class AnswerRateSmoother {

    /**
     * Calculates smoothed answer rate using Bayesian m-estimate.
     * P_smooth = (observed_answers + m * prior) / (total_attempts + m)
     *
     * @param observedAnswers count of answered calls
     * @param totalAttempts count of completed or failed call attempts
     * @param priorDefaultRate baseline default rate (e.g. 0.30)
     * @param minSampleSize weight of prior (e.g. 20 observations)
     * @return smoothed probability between 0.05 and 0.95
     */
    public double calculateSmoothedAnswerRate(long observedAnswers,
                                             long totalAttempts,
                                             double priorDefaultRate,
                                             int minSampleSize) {
        if (priorDefaultRate <= 0) priorDefaultRate = 0.30;
        if (minSampleSize <= 0) minSampleSize = 20;

        double smoothed = (observedAnswers + (minSampleSize * priorDefaultRate)) /
                          (totalAttempts + minSampleSize);

        // Clamp to safe bounds [0.05, 0.95] to prevent division by zero or infinite dialing
        return Math.max(0.05, Math.min(0.95, smoothed));
    }
}
