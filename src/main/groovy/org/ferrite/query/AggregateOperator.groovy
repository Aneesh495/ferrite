package org.ferrite.query

import groovy.transform.CompileStatic
import org.ferrite.vector.ColumnVector
import org.ferrite.vector.ColumnarBatch
import org.ferrite.vector.DoubleColumnVector

/**
 * Streaming vectorized aggregation operator.
 * Employs Welford's single-pass algorithm for numerical stability in online mean and variance computation.
 */
@CompileStatic
class AggregateOperator implements PhysicalOperator {
    private final PhysicalOperator child
    private final AggregationType aggregationType
    private final String valueColumnName

    private long count = 0L
    private double sum = 0.0
    private double min = Double.MAX_VALUE
    private double max = -Double.MAX_VALUE
    private double mean = 0.0
    private double m2 = 0.0
    private double lastValue = 0.0
    private boolean emitted = false

    AggregateOperator(PhysicalOperator child, AggregationType type) {
        this(child, type, "value")
    }

    AggregateOperator(PhysicalOperator child, AggregationType type, String valueColumnName) {
        if (child == null || type == null) {
            throw new IllegalArgumentException("Child and aggregation type cannot be null")
        }
        this.child = child
        this.aggregationType = type
        this.valueColumnName = valueColumnName
    }

    @Override
    void open() {
        child.open()
        count = 0L
        sum = 0.0
        min = Double.MAX_VALUE
        max = -Double.MAX_VALUE
        mean = 0.0
        m2 = 0.0
        lastValue = 0.0
        emitted = false
    }

    @Override
    ColumnarBatch next() {
        if (emitted) return null

        ColumnarBatch batch
        while ((batch = child.next()) != null) {
            DoubleColumnVector col = (DoubleColumnVector) batch.column(valueColumnName)
            if (col == null) continue

            int n = batch.numRows()
            for (int r = 0; r < n; r++) {
                int actualRow = batch.getActualRowIndex(r)
                if (col.isNullAt(actualRow)) continue

                double x = col.getDouble(actualRow)
                count++
                sum += x
                if (x < min) min = x
                if (x > max) max = x
                lastValue = x

                // Welford's algorithm
                double delta = x - mean
                mean += delta / (double) count
                double delta2 = x - mean
                m2 += delta * delta2
            }
        }

        emitted = true
        double finalResult = computeFinalResult()

        DoubleColumnVector resultVec = new DoubleColumnVector([finalResult] as double[])
        ColumnarBatch.of(
                [aggregationType.name().toLowerCase()] as String[],
                [resultVec] as ColumnVector[],
                1
        )
    }

    private double computeFinalResult() {
        if (count == 0L) return 0.0
        switch (aggregationType) {
            case AggregationType.COUNT:
                return (double) count
            case AggregationType.SUM:
                return sum
            case AggregationType.MIN:
                return min
            case AggregationType.MAX:
                return max
            case AggregationType.AVG:
                return mean
            case AggregationType.STDDEV:
                if (count > 1L) {
                    return Math.sqrt(m2 / (double) (count - 1L))
                }
                return 0.0
            case AggregationType.LAST:
                return lastValue
            default:
                return mean
        }
    }

    @Override
    void close() {
        child.close()
    }
}
