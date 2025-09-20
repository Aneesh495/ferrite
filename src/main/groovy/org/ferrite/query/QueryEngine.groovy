package org.ferrite.query

import groovy.transform.CompileStatic
import org.ferrite.common.MetricPoint
import org.ferrite.common.TimeRange
import org.ferrite.common.TimeSeriesKey
import org.ferrite.storage.StorageEngine
import org.ferrite.vector.ColumnarBatch
import org.ferrite.vector.DoubleColumnVector
import org.ferrite.vector.TimestampColumnVector

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ForkJoinPool

/**
 * High-throughput vectorized query execution engine.
 * Dispatches physical operator pipelines across thread pools and collects aggregated data frames.
 */
@CompileStatic
class QueryEngine {
    private final StorageEngine storageEngine
    private final ForkJoinPool executorPool

    QueryEngine(StorageEngine storageEngine) {
        this(storageEngine, ForkJoinPool.commonPool())
    }

    QueryEngine(StorageEngine storageEngine, ForkJoinPool executorPool) {
        if (storageEngine == null) {
            throw new IllegalArgumentException("StorageEngine cannot be null")
        }
        this.storageEngine = storageEngine
        this.executorPool = executorPool != null ? executorPool : ForkJoinPool.commonPool()
    }

    QueryResult execute(QueryPlan plan) {
        long startNano = System.nanoTime()
        QueryContext ctx = new QueryContext(storageEngine)

        // 1. Identify candidate series using InvertedIndex
        List<TimeSeriesKey> matchedKeys = storageEngine.searchSeries(plan.metricName, plan.tagFilters)
        if (matchedKeys.isEmpty()) {
            long durationMs = (long) ((System.nanoTime() - startNano) / 1000000.0)
            return new QueryResult(Collections.<TimeSeriesKey, List<MetricPoint>>emptyMap(), 0L, durationMs)
        }

        Map<TimeSeriesKey, List<MetricPoint>> results = new ConcurrentHashMap<TimeSeriesKey, List<MetricPoint>>()
        long totalScanned = 0L

        // Execute across matched series
        for (TimeSeriesKey seriesKey : matchedKeys) {
            PhysicalOperator pipeline = plan.buildPipeline(ctx, seriesKey)
            List<MetricPoint> points = new ArrayList<MetricPoint>()

            pipeline.open()
            try {
                ColumnarBatch batch
                while ((batch = pipeline.next()) != null) {
                    TimestampColumnVector tsCol = (TimestampColumnVector) batch.column("timestamp")
                    DoubleColumnVector valCol = (DoubleColumnVector) batch.column("value")
                    if (valCol == null) {
                        // Aggregate output might name the column by aggregate type (e.g. "avg", "sum")
                        valCol = (DoubleColumnVector) batch.column(0)
                    }

                    int n = batch.numRows()
                    totalScanned += n
                    for (int i = 0; i < n; i++) {
                        int r = batch.getActualRowIndex(i)
                        long ts = tsCol != null ? tsCol.getLong(r) : 0L
                        double val = (valCol != null && !valCol.isNullAt(r)) ? valCol.getDouble(r) : 0.0d
                        points.add(MetricPoint.of(ts, val))
                    }
                }
            } finally {
                pipeline.close()
            }

            if (!points.isEmpty()) {
                results.put(seriesKey, points)
            }
        }

        long durationMs = (long) ((System.nanoTime() - startNano) / 1000000.0)
        new QueryResult(results, totalScanned, durationMs)
    }

    QueryResult executeRaw(TimeSeriesKey key, TimeRange range) {
        QueryPlan plan = new QueryPlan(
                key.metricName,
                key.tags.toMap(),
                range,
                null,
                null,
                null,
                null,
                false,
                1.0d
        )
        execute(plan)
    }

    QueryResult executeWindowed(TimeSeriesKey key, TimeRange range, long windowSizeMs, AggregationType agg) {
        QueryPlan plan = new QueryPlan(
                key.metricName,
                key.tags.toMap(),
                range,
                windowSizeMs,
                agg,
                null,
                null,
                false,
                1.0d
        )
        execute(plan)
    }

    QueryResult executeAggregate(TimeSeriesKey key, TimeRange range, AggregationType agg) {
        QueryPlan plan = new QueryPlan(
                key.metricName,
                key.tags.toMap(),
                range,
                null,
                agg,
                null,
                null,
                false,
                1.0d
        )
        execute(plan)
    }
}
