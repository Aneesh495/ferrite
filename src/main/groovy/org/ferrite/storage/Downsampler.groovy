package org.ferrite.storage

import groovy.transform.CompileStatic
import org.ferrite.common.MetricPoint
import org.ferrite.common.TimeSeriesKey

/**
 * Multi-resolution downsampling engine.
 * Computes rollups (mean, min, max, sum, count, first, last) over temporal buckets
 * for high-performance long-range historical queries.
 */
@CompileStatic
class Downsampler {

    enum RollupAgg {
        MEAN, MIN, MAX, SUM, COUNT, FIRST, LAST
    }

    static List<MetricPoint> downsample(List<MetricPoint> points, long bucketSizeMs, RollupAgg agg) {
        if (points == null || points.isEmpty() || bucketSizeMs <= 0L) {
            return Collections.emptyList()
        }

        Map<Long, List<Double>> buckets = new TreeMap<>()
        for (MetricPoint pt : points) {
            long bucket = (long) (pt.timestamp / (double) bucketSizeMs) * bucketSizeMs
            List<Double> list = buckets.computeIfAbsent(bucket, { k -> new ArrayList<Double>() })
            list.add(pt.value)
        }

        List<MetricPoint> rollups = new ArrayList<>(buckets.size())
        for (Map.Entry<Long, List<Double>> entry : buckets.entrySet()) {
            long bucketTime = entry.key
            List<Double> vals = entry.value
            double aggregateValue = computeAggregate(vals, agg)
            rollups.add(MetricPoint.of(bucketTime, aggregateValue))
        }

        rollups
    }

    static TimeSeriesKey rollupKey(TimeSeriesKey originalKey, String resolution, RollupAgg agg) {
        String newName = originalKey.metricName + "_rollup_" + resolution.toLowerCase()
        originalKey.tags.withTag("agg", agg.name().toLowerCase())
        TimeSeriesKey.of(newName, originalKey.tags.withTag("agg", agg.name().toLowerCase()))
    }

    private static double computeAggregate(List<Double> vals, RollupAgg agg) {
        if (vals.isEmpty()) return 0.0
        switch (agg) {
            case RollupAgg.FIRST:
                return vals.get(0)
            case RollupAgg.LAST:
                return vals.get(vals.size() - 1)
            case RollupAgg.MIN:
                double min = Double.MAX_VALUE
                for (double v : vals) {
                    if (v < min) min = v
                }
                return min
            case RollupAgg.MAX:
                double max = -Double.MAX_VALUE
                for (double v : vals) {
                    if (v > max) max = v
                }
                return max
            case RollupAgg.SUM:
                double sum = 0.0
                for (double v : vals) {
                    sum += v
                }
                return sum
            case RollupAgg.COUNT:
                return (double) vals.size()
            case RollupAgg.MEAN:
            default:
                double total = 0.0
                for (double v : vals) {
                    total += v
                }
                return total / (double) vals.size()
        }
    }

    private Downsampler() {}
}
