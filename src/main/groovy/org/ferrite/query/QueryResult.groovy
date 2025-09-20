package org.ferrite.query

import groovy.transform.CompileStatic
import org.ferrite.common.MetricPoint
import org.ferrite.common.TimeSeriesKey

@CompileStatic
class QueryResult {
    final Map<TimeSeriesKey, List<MetricPoint>> series
    final long samplesScanned
    final long executionTimeMs

    QueryResult(Map<TimeSeriesKey, List<MetricPoint>> series, long samplesScanned, long executionTimeMs) {
        Map<TimeSeriesKey, List<MetricPoint>> copy = new LinkedHashMap<TimeSeriesKey, List<MetricPoint>>()
        if (series != null) {
            copy.putAll(series)
        }
        this.series = Collections.unmodifiableMap(copy)
        this.samplesScanned = samplesScanned
        this.executionTimeMs = executionTimeMs
    }

    int totalSeries() {
        series.size()
    }

    long totalPoints() {
        long count = 0L
        for (List<MetricPoint> pts : series.values()) {
            count += pts.size()
        }
        count
    }
}
