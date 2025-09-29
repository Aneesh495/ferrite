package org.ferrite.analytics

import groovy.transform.CompileStatic

/**
 * Holt-Winters double and triple exponential smoothing algorithm for
 * stream-oriented forecasting and anomaly detection on telemetry metrics.
 *
 * Supports additive seasonality, linear trend, online single-point updating,
 * and batch fitting with dynamic residual variance estimation.
 */
@CompileStatic
class HoltWinters {

    private final double alpha
    private final double beta
    private final double gamma
    private final int period
    private final boolean seasonal

    private double level = Double.NaN
    private double trend = Double.NaN
    private final double[] seasonals
    private double residualVariance = 0.0d
    private long sampleCount = 0L

    /**
     * Constructs a Holt-Winters forecaster.
     *
     * @param alpha smoothing factor for data level (0.0 < alpha <= 1.0)
     * @param beta smoothing factor for trend (0.0 <= beta <= 1.0)
     * @param gamma smoothing factor for seasonal component (0.0 <= gamma <= 1.0)
     * @param period length of seasonal cycle (e.g. 24 for hourly day cycle). 0 or 1 disables seasonality.
     */
    HoltWinters(double alpha, double beta, double gamma = 0.0d, int period = 0) {
        if (alpha <= 0.0d || alpha > 1.0d) {
            throw new IllegalArgumentException("Alpha must be in (0, 1], got: ${alpha}")
        }
        if (beta < 0.0d || beta > 1.0d) {
            throw new IllegalArgumentException("Beta must be in [0, 1], got: ${beta}")
        }
        if (gamma < 0.0d || gamma > 1.0d) {
            throw new IllegalArgumentException("Gamma must be in [0, 1], got: ${gamma}")
        }
        this.alpha = alpha
        this.beta = beta
        this.gamma = gamma
        this.period = Math.max(0, period)
        this.seasonal = this.period > 1 && gamma > 0.0d
        this.seasonals = this.seasonal ? new double[this.period] : new double[0]
    }

    /**
     * Initializes the model with an initial training series.
     * Requires at least two full periods if seasonal, or 2 samples for non-seasonal.
     */
    synchronized void fit(double[] history) {
        if (history == null || history.length < 2) {
            throw new IllegalArgumentException("Fitting requires at least 2 samples")
        }

        if (!seasonal || history.length < 2 * period) {
            // Fit double exponential (Holt's linear trend)
            level = history[0]
            trend = history[1] - history[0]
            sampleCount = 2L
            residualVariance = 0.0d

            for (int i = 2; i < history.length; i++) {
                update(history[i])
            }
            return
        }

        // Initialize seasonal indices and base level/trend
        double sumSeason1 = 0.0d
        double sumSeason2 = 0.0d
        for (int i = 0; i < period; i++) {
            sumSeason1 += history[i]
            sumSeason2 += history[i + period]
        }
        double avg1 = sumSeason1 / (double) period
        double avg2 = sumSeason2 / (double) period

        level = avg2
        trend = (avg2 - avg1) / (double) period

        // Initialize additive seasonal components
        for (int i = 0; i < period; i++) {
            seasonals[i] = (history[i] - avg1 + history[i + period] - avg2) / 2.0d
        }

        sampleCount = (long) (2 * period)
        residualVariance = 0.0d

        // Feed remaining history
        for (int i = 2 * period; i < history.length; i++) {
            update(history[i])
        }
    }

    /**
     * Ingests a new observation online, computes residual against forecast,
     * updates internal states (level, trend, seasonal, variance), and returns
     * the one-step ahead forecast error (actual - predicted).
     */
    synchronized double update(double actual) {
        if (Double.isNaN(level)) {
            level = actual
            trend = 0.0d
            sampleCount = 1L
            return 0.0d
        }
        if (Double.isNaN(trend)) {
            trend = actual - level
            level = actual
            sampleCount = 2L
            return 0.0d
        }

        int seasonIdx = seasonal ? (int) (sampleCount % (long) period) : 0
        double seasonalComp = seasonal ? seasonals[seasonIdx] : 0.0d
        double predicted = level + trend + seasonalComp
        double error = actual - predicted

        // Update level and trend
        double prevLevel = level
        double prevTrend = trend

        if (seasonal) {
            level = alpha * (actual - seasonalComp) + (1.0d - alpha) * (prevLevel + prevTrend)
            trend = beta * (level - prevLevel) + (1.0d - beta) * prevTrend
            seasonals[seasonIdx] = gamma * (actual - level) + (1.0d - gamma) * seasonalComp
        } else {
            level = alpha * actual + (1.0d - alpha) * (prevLevel + prevTrend)
            trend = beta * (level - prevLevel) + (1.0d - beta) * prevTrend
        }

        // Welford-style variance update of forecast error
        sampleCount++
        double delta = Math.abs(error)
        residualVariance = 0.95d * residualVariance + 0.05d * (delta * delta)

        return error
    }

    /**
     * Forecasts horizon h steps ahead into the future from current state.
     * h is 1-indexed (1 is next step).
     */
    synchronized double forecast(int h) {
        if (h <= 0) {
            throw new IllegalArgumentException("Forecast horizon h must be >= 1")
        }
        if (Double.isNaN(level)) {
            return 0.0d
        }

        double base = level + (double) h * (Double.isNaN(trend) ? 0.0d : trend)
        if (!seasonal) {
            return base
        }

        int idx = (int) ((sampleCount + (long) h - 1L) % (long) period)
        return base + seasonals[idx]
    }

    /**
     * Checks whether an observation is an anomaly based on k standard deviations.
     */
    synchronized boolean isAnomaly(double actual, double sigmaMultiplier = 3.0d) {
        if (sampleCount < 4L) {
            return false
        }
        double stdDev = Math.sqrt(residualVariance)
        if (stdDev < 1e-9d) {
            return false
        }
        double predicted = forecast(1)
        return Math.abs(actual - predicted) > (sigmaMultiplier * stdDev)
    }

    double getLevel() { level }
    double getTrend() { trend }
    double getResidualVariance() { residualVariance }
    long getSampleCount() { sampleCount }
}
