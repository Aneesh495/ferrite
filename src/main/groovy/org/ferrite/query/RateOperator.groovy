package org.ferrite.query

import groovy.transform.CompileStatic
import org.ferrite.vector.ColumnVector
import org.ferrite.vector.ColumnarBatch
import org.ferrite.vector.DoubleColumnVector
import org.ferrite.vector.TimestampColumnVector

/**
 * Instantaneous rate of change operator.
 * Derives per-second rate of change over monotonically increasing counters with automatic reset handling.
 */
@CompileStatic
class RateOperator implements PhysicalOperator {
    private final PhysicalOperator child
    private Long lastTimestamp = null
    private Double lastValue = null

    RateOperator(PhysicalOperator child) {
        if (child == null) {
            throw new IllegalArgumentException("Child operator cannot be null")
        }
        this.child = child
    }

    @Override
    void open() {
        child.open()
        lastTimestamp = null
        lastValue = null
    }

    @Override
    ColumnarBatch next() {
        while (true) {
            ColumnarBatch batch = child.next()
            if (batch == null) return null

            TimestampColumnVector tsCol = (TimestampColumnVector) batch.column("timestamp")
            DoubleColumnVector valCol = (DoubleColumnVector) batch.column("value")
            if (tsCol == null || valCol == null) continue

            int n = batch.numRows()
            if (n == 0) continue

            List<Long> outTs = new ArrayList<>(n)
            List<Double> outVals = new ArrayList<>(n)

            for (int r = 0; r < n; r++) {
                int actualRow = batch.getActualRowIndex(r)
                if (valCol.isNullAt(actualRow)) continue

                long currentTs = tsCol.getLong(actualRow)
                double currentVal = valCol.getDouble(actualRow)

                if (lastTimestamp != null && lastValue != null) {
                    long dtMs = currentTs - lastTimestamp
                    if (dtMs > 0L) {
                        double dtSec = (double) dtMs / 1000.0
                        double dv = currentVal - lastValue
                        double rateVal = dv >= 0.0 ? (double) (dv / dtSec) : (double) 0.0
                        outTs.add(currentTs)
                        outVals.add(rateVal)
                    }
                }

                lastTimestamp = currentTs
                lastValue = currentVal
            }

            int count = outTs.size()
            if (count > 0) {
                long[] tsArr = new long[count]
                double[] valArr = new double[count]
                for (int i = 0; i < count; i++) {
                    tsArr[i] = outTs.get(i)
                    valArr[i] = outVals.get(i)
                }

                return ColumnarBatch.of(
                        ["timestamp", "value"] as String[],
                        [new TimestampColumnVector(tsArr), new DoubleColumnVector(valArr)] as ColumnVector[],
                        count
                )
            }
        }
    }

    @Override
    void close() {
        child.close()
    }
}
