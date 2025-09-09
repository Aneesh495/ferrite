package org.ferrite.query

import groovy.transform.CompileStatic
import org.ferrite.common.MetricPoint
import org.ferrite.common.TimeRange
import org.ferrite.common.TimeSeriesKey
import org.ferrite.storage.StorageEngine
import org.ferrite.vector.ColumnVector
import org.ferrite.vector.ColumnarBatch
import org.ferrite.vector.DoubleColumnVector
import org.ferrite.vector.TimestampColumnVector

/**
 * Physical table scan operator.
 * Reads time series data slices from the underlying StorageEngine and emits contiguous ColumnarBatch chunks.
 */
@CompileStatic
class ScanOperator implements PhysicalOperator {
    private static final int DEFAULT_BATCH_SIZE = 1024

    private final StorageEngine storageEngine
    private final TimeSeriesKey seriesKey
    private final TimeRange range
    private final int batchSize

    private List<MetricPoint> points
    private int cursor
    private boolean opened

    ScanOperator(StorageEngine storageEngine, TimeSeriesKey seriesKey, TimeRange range) {
        this(storageEngine, seriesKey, range, DEFAULT_BATCH_SIZE)
    }

    ScanOperator(StorageEngine storageEngine, TimeSeriesKey seriesKey, TimeRange range, int batchSize) {
        this.storageEngine = storageEngine
        this.seriesKey = seriesKey
        this.range = range
        this.batchSize = batchSize
        this.cursor = 0
        this.opened = false
    }

    @Override
    void open() {
        this.points = storageEngine.query(seriesKey, range)
        this.cursor = 0
        this.opened = true
    }

    @Override
    ColumnarBatch next() {
        if (!opened || points == null || cursor >= points.size()) {
            return null
        }

        int remaining = points.size() - cursor
        int count = Math.min(batchSize, remaining)

        long[] tsArray = new long[count]
        double[] valArray = new double[count]

        for (int i = 0; i < count; i++) {
            MetricPoint pt = points.get(cursor + i)
            tsArray[i] = pt.timestamp
            valArray[i] = pt.value
        }
        cursor += count

        TimestampColumnVector tsVec = new TimestampColumnVector(tsArray)
        DoubleColumnVector valVec = new DoubleColumnVector(valArray)

        ColumnarBatch.of(
                ["timestamp", "value"] as String[],
                [tsVec, valVec] as ColumnVector[],
                count
        )
    }

    @Override
    void close() {
        this.points = null
        this.opened = false
    }

    TimeSeriesKey getSeriesKey() {
        seriesKey
    }
}
