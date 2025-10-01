package org.ferrite.analytics

import groovy.transform.CompileStatic

/**
 * Cumulative Sum (CUSUM) statistical quality control change-point detector.
 * Detects abrupt persistent mean shifts in continuous time-series metric streams.
 *
 * Implements two-sided tabular CUSUM with allowance parameter k (slack)
 * and decision threshold h.
 */
@CompileStatic
class CUSUMDetector {

    private final double slack
    private final double threshold
    private final boolean adaptiveMean

    private double targetMean = 0.0d
    private double positiveSum = 0.0d
    private double negativeSum = 0.0d
    private long sampleCount = 0L

    // Online running stats for target calibration
    private double runningMean = 0.0d
    private double runningM2 = 0.0d

    /**
     * @param slack allowance parameter k (typically 0.5 * detectable shift size)
     * @param threshold decision boundary h (typically 4.0 to 5.0 standard deviations)
     * @param targetMean known baseline mean, or 0.0 if calibrated adaptively
     * @param adaptiveMean true to continuously calibrate baseline mean
     */
    CUSUMDetector(double slack, double threshold, double targetMean = 0.0d, boolean adaptiveMean = false) {
        if (slack < 0.0d) {
            throw new IllegalArgumentException("Slack must be non-negative: ${slack}")
        }
        if (threshold <= 0.0d) {
            throw new IllegalArgumentException("Threshold must be positive: ${threshold}")
        }
        this.slack = slack
        this.threshold = threshold
        this.targetMean = targetMean
        this.adaptiveMean = adaptiveMean
    }

    /**
     * Ingests a new observation, updates positive/negative sums, and returns
     * true if an upward or downward shift exceeds the decision threshold.
     */
    synchronized boolean update(double x) {
        sampleCount++

        // Calibrate running mean and variance if adaptive or initial warmup
        if (adaptiveMean || sampleCount <= 30L) {
            double delta = x - runningMean
            runningMean += delta / (double) sampleCount
            double delta2 = x - runningMean
            runningM2 += delta * delta2
            if (sampleCount <= 30L && !adaptiveMean) {
                targetMean = runningMean
            }
        }

        double mu = adaptiveMean ? runningMean : targetMean

        // Update high and low CUSUM statistics
        positiveSum = Math.max(0.0d, positiveSum + (x - mu - slack))
        negativeSum = Math.max(0.0d, negativeSum + (-x + mu - slack))

        boolean breach = positiveSum > threshold || negativeSum > threshold
        if (breach) {
            // Reset sums after detection to allow subsequent detections
            resetSums()
        }
        return breach
    }

    synchronized void resetSums() {
        positiveSum = 0.0d
        negativeSum = 0.0d
    }

    double getPositiveSum() { positiveSum }
    double getNegativeSum() { negativeSum }
    double getTargetMean() { targetMean }
    long getSampleCount() { sampleCount }
    double getStandardDeviation() {
        if (sampleCount < 2L) return 0.0d
        return Math.sqrt(runningM2 / (double) (sampleCount - 1L))
    }
}
