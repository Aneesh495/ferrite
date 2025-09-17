package org.ferrite.query

import groovy.transform.CompileStatic
import org.ferrite.vector.ColumnVector
import org.ferrite.vector.ColumnarBatch
import org.ferrite.vector.DoubleColumnVector
import org.ferrite.vector.TimestampColumnVector

/**
 * Temporal window aggregation operator supporting tumbling and sliding windows.
 */
@CompileStatic
class WindowOperator implements PhysicalOperator {
    private final PhysicalOperator child
    private final long windowSizeMs
    private final long slideMs
    private final AggregationType aggregationType

    private boolean consumed = false
    private final Map<Long, List<Double>> windowBuckets = new TreeMap<>()
    private Iterator<Map.Entry<Long, List<Double>>> bucketIterator

    WindowOperator(PhysicalOperator child, long windowSizeMs, AggregationType aggregationType) {
        this(child, windowSizeMs, windowSizeMs, aggregationType)
    }

    WindowOperator(PhysicalOperator child, long windowSizeMs, long slideMs, AggregationType aggregationType) {
        if (child == null || windowSizeMs <= 0L || slideMs <= 0L || aggregationType == null) {
            throw new IllegalArgumentException("Invalid window parameters")
        }
        this.child = child
        this.windowSizeMs = windowSizeMs
        this.slideMs = slideMs
        this.aggregationType = aggregationType
    }

    @Override
    void open() {
        child.open()
        windowBuckets.clear()
        consumed = false
    }

    @Override
    ColumnarBatch next() {
        if (!consumed) {
            consumeChildBatches()
            consumed = true
            bucketIterator = windowBuckets.entrySet().iterator()
        }

        if (!bucketIterator.hasNext()) {
            return null
        }

        int maxBatch = 1024
        List<Long> tsList = new ArrayList<>()
        List<Double> valList = new ArrayList<>()

        while (bucketIterator.hasNext() && tsList.size() < maxBatch) {
            Map.Entry<Long, List<Double>> entry = bucketIterator.next()
            long windowStart = entry.key
            List<Double> values = entry.value

            double aggregatedVal = aggregateWindow(values, aggregationType)
            tsList.add(windowStart)
            valList.add(aggregatedVal)
        }

        int count = tsList.size()
        long[] tsArr = new long[count]
        double[] valArr = new double[count]
        for (int i = 0; i < count; i++) {
            tsArr[i] = tsList.get(i)
            valArr[i] = valList.get(i)
        }

        TimestampColumnVector tsVec = new TimestampColumnVector(tsArr)
        DoubleColumnVector valVec = new DoubleColumnVector(valArr)

        ColumnarBatch.of(
                ["timestamp", "value"] as String[],
                [tsVec, valVec] as ColumnVector[],
                count
        )
    }

    private void consumeChildBatches() {
        ColumnarBatch batch
        while ((batch = child.next()) != null) {
            TimestampColumnVector tsCol = (TimestampColumnVector) batch.column("timestamp")
            DoubleColumnVector valCol = (DoubleColumnVector) batch.column("value")
            if (tsCol == null || valCol == null) continue

            int n = batch.numRows()
            for (int r = 0; r < n; r++) {
                int actualRow = batch.getActualRowIndex(r)
                if (valCol.isNullAt(actualRow)) continue

                long ts = tsCol.getLong(actualRow)
                double val = valCol.getDouble(actualRow)

                // Assign to tumbling or sliding window buckets
                if (slideMs == windowSizeMs) {
                    long bucket = (long) (ts / (double) windowSizeMs) * windowSizeMs
                    List<Double> list = windowBuckets.computeIfAbsent(bucket, { k -> new ArrayList<Double>() })
                    list.add(val)
                } else {
                    long firstBucket = (long) (((double) (ts - windowSizeMs + slideMs) / (double) slideMs)) * slideMs
                    long lastBucket = (long) (((double) ts / (double) slideMs)) * slideMs
                    for (long b = firstBucket; b <= lastBucket; b += slideMs) {
                        if (ts >= b && ts < b + windowSizeMs) {
                            List<Double> list = windowBuckets.computeIfAbsent(b, { k -> new ArrayList<Double>() })
                            list.add(val)
                        }
                    }
                }
            }
        }
    }

    private static double aggregateWindow(List<Double> values, AggregationType agg) {
        if (values.isEmpty()) return 0.0
        switch (agg) {
            case AggregationType.COUNT:
                return (double) values.size()
            case AggregationType.SUM:
                double sum = 0.0
                for (double v : values) sum += v
                return sum
            case AggregationType.MIN:
                double min = Double.MAX_VALUE
                for (double v : values) if (v < min) min = v
                return min
            case AggregationType.MAX:
                double max = -Double.MAX_VALUE
                for (double v : values) if (v > max) max = v
                return max
            case AggregationType.LAST:
                return values.get(values.size() - 1)
            case AggregationType.AVG:
            default:
                double total = 0.0
                for (double v : values) total += v
                return total / (double) values.size()
        }
    }

    @Override
    void close() {
        child.close()
        windowBuckets.clear()
    }
}
