package org.ferrite.dsl

import groovy.transform.CompileStatic
import org.ferrite.common.TimeRange
import org.ferrite.query.AggregationType
import org.ferrite.query.QueryPlan

/**
 * Fluent query specification builder for time-series analytics.
 * Supports human-readable durations, closure delegates, and pushes down
 * to an optimized QueryPlan.
 */
@CompileStatic
class QueryBuilder {

    private String metricName
    private final Map<String, String> tagFilters = new LinkedHashMap<String, String>()
    private TimeRange timeRange = TimeRange.allTime()
    private Long windowDurationMs = null
    private AggregationType aggregation = null
    private Double minThreshold = null
    private Double maxThreshold = null
    private boolean computeRate = false
    private double scaleMultiplier = 1.0d

    /**
     * Sets target metric series name.
     */
    QueryBuilder from(String name) {
        this.metricName = name
        this
    }

    /**
     * Adds tag equality filter.
     */
    QueryBuilder tag(String key, String value) {
        this.tagFilters.put(key, value)
        this
    }

    /**
     * Adds multiple tag filters.
     */
    QueryBuilder tags(Map<String, String> tags) {
        if (tags != null) {
            this.tagFilters.putAll(tags)
        }
        this
    }

    /**
     * Sets query time range using relative duration (e.g. "15m", "1h", "7d").
     */
    QueryBuilder range(String humanDuration) {
        this.timeRange = TimeRange.last(humanDuration)
        this
    }

    /**
     * Sets explicit start and end epoch timestamps in milliseconds.
     */
    QueryBuilder between(long startMs, long endMs) {
        this.timeRange = TimeRange.between(startMs, endMs)
        this
    }

    /**
     * Adds a minimum value threshold.
     */
    QueryBuilder min(double threshold) {
        this.minThreshold = threshold
        this
    }

    /**
     * Adds a maximum value threshold.
     */
    QueryBuilder max(double threshold) {
        this.maxThreshold = threshold
        this
    }

    /**
     * Enables per-second instant derivative calculation.
     */
    QueryBuilder rate(boolean enable = true) {
        this.computeRate = enable
        this
    }

    /**
     * Applies scaling multiplier to values.
     */
    QueryBuilder scale(double factor) {
        this.scaleMultiplier = factor
        this
    }

    /**
     * Configures tumbling aggregation window.
     */
    QueryBuilder every(String humanDuration, String agg = "avg") {
        this.windowDurationMs = TimeRange.parseDuration(humanDuration).toMillis()
        this.aggregation = AggregationType.valueOf(agg.toUpperCase().trim())
        this
    }

    QueryBuilder every(long windowMs, AggregationType agg) {
        this.windowDurationMs = windowMs
        this.aggregation = agg
        this
    }

    /**
     * Compiles the builder configuration into an immutable QueryPlan.
     */
    QueryPlan build() {
        if (metricName == null || metricName.trim().isEmpty()) {
            throw new IllegalStateException("Query requires a target metric name (use: from 'name')")
        }

        new QueryPlan(
                metricName,
                tagFilters,
                timeRange,
                windowDurationMs,
                aggregation,
                minThreshold,
                maxThreshold,
                computeRate,
                scaleMultiplier
        )
    }

    String getMetricName() { metricName }
    Map<String, String> getTagFilters() { Collections.unmodifiableMap(tagFilters) }
    TimeRange getTimeRange() { timeRange }
    Long getWindowDurationMs() { windowDurationMs }
    AggregationType getAggregation() { aggregation }
    Double getMinThreshold() { minThreshold }
    Double getMaxThreshold() { maxThreshold }
    boolean isComputeRate() { computeRate }
    double getScaleMultiplier() { scaleMultiplier }
}
