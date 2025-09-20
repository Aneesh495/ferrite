package org.ferrite.query

import groovy.transform.CompileStatic
import org.ferrite.common.TimeRange
import org.ferrite.common.TimeSeriesKey
import org.ferrite.vector.ColumnarBatch
import org.ferrite.vector.DoubleColumnVector

/**
 * Declarative logical and physical query plan builder.
 * Constructs optimized operator trees and applies pushdown transformations.
 */
@CompileStatic
class QueryPlan {
    final String metricName
    final Map<String, String> tagFilters
    final TimeRange timeRange
    final Long windowSizeMs
    final AggregationType aggregation
    final Double minThreshold
    final Double maxThreshold
    final boolean computeRate
    final double scaleMultiplier

    QueryPlan(String metricName, Map<String, String> tagFilters, TimeRange timeRange,
              Long windowSizeMs, AggregationType aggregation, Double minThreshold, Double maxThreshold,
              boolean computeRate, double scaleMultiplier) {
        this.metricName = metricName
        Map<String, String> copy = new HashMap<String, String>()
        if (tagFilters != null) {
            copy.putAll(tagFilters)
        }
        this.tagFilters = Collections.unmodifiableMap(copy)
        this.timeRange = timeRange != null ? timeRange : TimeRange.allTime()
        this.windowSizeMs = windowSizeMs
        this.aggregation = aggregation
        this.minThreshold = minThreshold
        this.maxThreshold = maxThreshold
        this.computeRate = computeRate
        this.scaleMultiplier = scaleMultiplier != 0.0 ? scaleMultiplier : 1.0d
    }

    PhysicalOperator buildPipeline(QueryContext ctx, TimeSeriesKey seriesKey) {
        // 1. Leaf: Scan operator with predicate pushdown into StorageEngine
        PhysicalOperator op = new ScanOperator(ctx.storageEngine, seriesKey, timeRange)

        // 2. Rate transformation if requested
        if (computeRate) {
            op = new RateOperator(op)
        }

        // 3. Value filter pushdown
        if (minThreshold != null || maxThreshold != null) {
            final Double minT = minThreshold
            final Double maxT = maxThreshold
            op = new FilterOperator(op, new FilterPredicate() {
                @Override
                int filter(ColumnarBatch batch, int[] outSelection) {
                    DoubleColumnVector valCol = (DoubleColumnVector) batch.column("value")
                    if (valCol == null) return 0
                    int n = batch.numRows()
                    int matched = 0
                    for (int i = 0; i < n; i++) {
                        int r = batch.getActualRowIndex(i)
                        if (valCol.isNullAt(r)) continue
                        double v = valCol.getDouble(r)
                        if (minT != null && v < minT) continue
                        if (maxT != null && v > maxT) continue
                        outSelection[matched++] = r
                    }
                    matched
                }
            })
        }

        // 4. Window aggregation or global aggregation
        if (windowSizeMs != null && windowSizeMs > 0L && aggregation != null) {
            op = new WindowOperator(op, windowSizeMs, aggregation)
        } else if (aggregation != null) {
            op = new AggregateOperator(op, aggregation)
        }

        // 5. Projection scaling
        if (scaleMultiplier != 1.0d) {
            op = new ProjectOperator(op, ["value"] as String[], scaleMultiplier, 0.0d)
        }

        op
    }
}
