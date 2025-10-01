package org.ferrite.analytics

import groovy.transform.CompileStatic

/**
 * Exponentially Weighted Moving Average (EWMA) with dynamic volatility bands.
 * Provides adaptive upper and lower bounds for real-time streaming anomaly detection.
 */
@CompileStatic
class EWMABands {

    private final double alpha
    private final double beta
    private final double numSigmas

    private double mean = Double.NaN
    private double variance = 0.0d
    private long sampleCount = 0L

    /**
     * @param alpha smoothing weight for mean (0.0 < alpha <= 1.0)
     * @param beta smoothing weight for variance (0.0 < beta <= 1.0)
     * @param numSigmas multiplier for band width (e.g. 2.0 or 3.0)
     */
    EWMABands(double alpha = 0.2d, double beta = 0.1d, double numSigmas = 3.0d) {
        if (alpha <= 0.0d || alpha > 1.0d) {
            throw new IllegalArgumentException("Alpha must be in (0, 1]")
        }
        if (beta <= 0.0d || beta > 1.0d) {
            throw new IllegalArgumentException("Beta must be in (0, 1]")
        }
        if (numSigmas <= 0.0d) {
            throw new IllegalArgumentException("NumSigmas must be positive")
        }
        this.alpha = alpha
        this.beta = beta
        this.numSigmas = numSigmas
    }

    /**
     * Ingests a new metric point and updates the mean and volatility bands.
     * Returns true if point breaches either the upper or lower volatility band.
     */
    synchronized boolean update(double x) {
        sampleCount++
        if (Double.isNaN(mean)) {
            mean = x
            variance = 0.0d
            return false
        }

        double stdDev = Math.sqrt(variance)
        boolean breach = sampleCount > 5L && stdDev > 1e-9d && (x > (mean + numSigmas * stdDev) || x < (mean - numSigmas * stdDev))

        // Update EWMA mean
        double prevMean = mean
        mean = alpha * x + (1.0d - alpha) * prevMean

        // Update EWMA variance
        double diff = x - mean
        variance = beta * (diff * diff) + (1.0d - beta) * variance

        return breach
    }

    double getMean() { mean }
    double getVariance() { variance }
    double getStdDev() { Math.sqrt(variance) }
    double getUpperBand() { Double.isNaN(mean) ? Double.NaN : mean + numSigmas * Math.sqrt(variance) }
    double getLowerBand() { Double.isNaN(mean) ? Double.NaN : mean - numSigmas * Math.sqrt(variance) }
    long getSampleCount() { sampleCount }
}
