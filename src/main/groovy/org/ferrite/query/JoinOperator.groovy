package org.ferrite.query

import groovy.transform.CompileStatic
import org.ferrite.vector.ColumnVector
import org.ferrite.vector.ColumnarBatch
import org.ferrite.vector.DoubleColumnVector
import org.ferrite.vector.TimestampColumnVector

/**
 * Temporal equi-join operator aligning parallel time series streams.
 * Matches timestamps within a configurable temporal tolerance boundary (|t1 - t2| <= toleranceMs).
 */
@CompileStatic
class JoinOperator implements PhysicalOperator {
    private final PhysicalOperator leftChild
    private final PhysicalOperator rightChild
    private final long toleranceMs

    private final Map<Long, Double> leftData = new TreeMap<>()
    private final Map<Long, Double> rightData = new TreeMap<>()
    private Iterator<Map.Entry<Long, Double>> leftIterator
    private boolean consumed = false

    JoinOperator(PhysicalOperator leftChild, PhysicalOperator rightChild) {
        this(leftChild, rightChild, 0L)
    }

    JoinOperator(PhysicalOperator leftChild, PhysicalOperator rightChild, long toleranceMs) {
        if (leftChild == null || rightChild == null || toleranceMs < 0L) {
            throw new IllegalArgumentException("Invalid join arguments")
        }
        this.leftChild = leftChild
        this.rightChild = rightChild
        this.toleranceMs = toleranceMs
    }

    @Override
    void open() {
        leftChild.open()
        rightChild.open()
        leftData.clear()
        rightData.clear()
        consumed = false
    }

    @Override
    ColumnarBatch next() {
        if (!consumed) {
            consumeBothChildren()
            consumed = true
            leftIterator = leftData.entrySet().iterator()
        }

        if (!leftIterator.hasNext()) {
            return null
        }

        int maxBatch = 1024
        List<Long> matchedTs = new ArrayList<>()
        List<Double> leftVals = new ArrayList<>()
        List<Double> rightVals = new ArrayList<>()

        while (leftIterator.hasNext() && matchedTs.size() < maxBatch) {
            Map.Entry<Long, Double> leftEntry = leftIterator.next()
            long t1 = leftEntry.key
            double v1 = leftEntry.value

            Double v2 = null
            if (toleranceMs == 0L) {
                v2 = rightData.get(t1)
            } else {
                // Approximate temporal lookup
                for (Map.Entry<Long, Double> rEntry : rightData.entrySet()) {
                    if (Math.abs(rEntry.key - t1) <= toleranceMs) {
                        v2 = rEntry.value
                        break
                    }
                }
            }

            if (v2 != null) {
                matchedTs.add(t1)
                leftVals.add(v1)
                rightVals.add(v2)
            }
        }

        int count = matchedTs.size()
        if (count == 0) return null

        long[] tsArr = new long[count]
        double[] lArr = new double[count]
        double[] rArr = new double[count]

        for (int i = 0; i < count; i++) {
            tsArr[i] = matchedTs.get(i)
            lArr[i] = leftVals.get(i)
            rArr[i] = rightVals.get(i)
        }

        ColumnarBatch.of(
                ["timestamp", "left", "right"] as String[],
                [new TimestampColumnVector(tsArr), new DoubleColumnVector(lArr), new DoubleColumnVector(rArr)] as ColumnVector[],
                count
        )
    }

    private void consumeBothChildren() {
        ColumnarBatch b
        while ((b = leftChild.next()) != null) {
            TimestampColumnVector tsCol = (TimestampColumnVector) b.column("timestamp")
            DoubleColumnVector valCol = (DoubleColumnVector) b.column("value")
            if (tsCol == null || valCol == null) continue
            int n = b.numRows()
            for (int i = 0; i < n; i++) {
                int r = b.getActualRowIndex(i)
                if (!valCol.isNullAt(r)) {
                    leftData.put(tsCol.getLong(r), valCol.getDouble(r))
                }
            }
        }

        while ((b = rightChild.next()) != null) {
            TimestampColumnVector tsCol = (TimestampColumnVector) b.column("timestamp")
            DoubleColumnVector valCol = (DoubleColumnVector) b.column("value")
            if (tsCol == null || valCol == null) continue
            int n = b.numRows()
            for (int i = 0; i < n; i++) {
                int r = b.getActualRowIndex(i)
                if (!valCol.isNullAt(r)) {
                    rightData.put(tsCol.getLong(r), valCol.getDouble(r))
                }
            }
        }
    }

    @Override
    void close() {
        leftChild.close()
        rightChild.close()
        leftData.clear()
        rightData.clear()
    }
}
