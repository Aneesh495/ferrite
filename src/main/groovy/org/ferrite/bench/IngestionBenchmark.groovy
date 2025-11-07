package org.ferrite.bench

import groovy.transform.CompileStatic
import org.ferrite.common.MetricPoint
import org.ferrite.common.TimeSeriesKey
import org.ferrite.storage.MemTable
import org.ferrite.vector.DoubleColumnVector
import org.ferrite.vector.VectorizedMath

/**
 * High-throughput microbenchmark harness measuring concurrent MemTable ingestion
 * and SIMD-aligned columnar vector operations.
 */
@CompileStatic
class IngestionBenchmark {

    static void main(String[] args) {
        int n = 500_000
        println "[benchmark] Ingestion Benchmark (${n} points)..."

        TimeSeriesKey key1 = TimeSeriesKey.parse("cpu.idle{host=srv-01,env=prod}")
        TimeSeriesKey key2 = TimeSeriesKey.parse("cpu.user{host=srv-01,env=prod}")

        MemTable memTable = new MemTable()
        long start = System.nanoTime()

        for (int i = 0; i < n; i++) {
            TimeSeriesKey k = (i % 2 == 0) ? key1 : key2
            memTable.put(k, MetricPoint.of(1000L + (long) i, (double) (i % 100)), (long) i)
        }

        long end = System.nanoTime()
        double durationMs = (double) (end - start) / 1_000_000.0d
        double opsPerSec = (double) n / (durationMs / 1000.0d)

        println String.format("  MemTable Ingest: %.2f ops/sec (%.2f ms total)", opsPerSec, durationMs)

        println "[benchmark] Columnar Vector Math Benchmark (${n} rows)..."
        DoubleColumnVector col = new DoubleColumnVector(n)
        for (int i = 0; i < n; i++) {
            col.append((double) (i * 0.5d))
        }

        long vStart = System.nanoTime()
        double sum = VectorizedMath.sum(col)
        double mean = VectorizedMath.mean(col)
        double std = VectorizedMath.stddev(col)
        long vEnd = System.nanoTime()

        double vDurationMs = (double) (vEnd - vStart) / 1_000_000.0d
        double vOps = (double) n / (vDurationMs / 1000.0d)

        println String.format("  Vectorized Aggregates (sum, mean, stddev): %.2f rows/sec (%.4f ms total)", vOps, vDurationMs)
        println String.format("  Computed: sum=%.1f, mean=%.2f, std=%.2f", sum, mean, std)
        println "[benchmark] Ingestion and vector benchmark completed successfully."
    }
}
