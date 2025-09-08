package org.ferrite.vector

import spock.lang.Specification

class VectorSpec extends Specification {

    def "should test double and long column vectors with null bitmaps"() {
        given:
        def dVec = new DoubleColumnVector(16)
        def lVec = new LongColumnVector(16)

        when:
        dVec.append(1.5)
        dVec.append(2.5)
        dVec.append(3.5)
        dVec.setNullAt(1)

        lVec.append(100L)
        lVec.append(200L)

        then:
        dVec.size() == 3
        dVec.getDouble(0) == 1.5
        !dVec.isNullAt(0)
        dVec.isNullAt(1)
        !dVec.isNullAt(2)

        lVec.size() == 2
        lVec.getLong(1) == 200L
    }

    def "should test packed string column vector"() {
        given:
        def sVec = new StringColumnVector(8)

        when:
        sVec.append("us-east-1")
        sVec.append(null)
        sVec.append("prod-worker-99")

        then:
        sVec.size() == 3
        sVec.getString(0) == "us-east-1"
        sVec.isNullAt(1)
        sVec.getString(1) == null
        sVec.getString(2) == "prod-worker-99"
    }

    def "should test columnar batch with selection vector"() {
        given:
        def ts = new TimestampColumnVector([1000L, 2000L, 3000L, 4000L, 5000L] as long[])
        def vals = new DoubleColumnVector([10.0, 50.0, 20.0, 80.0, 30.0] as double[])
        def batch = ColumnarBatch.of(["timestamp", "val"] as String[], [ts, vals] as ColumnVector[], 5)

        expect:
        batch.numRows() == 5
        batch.numColumns() == 2
        batch.doubleColumn("val").getDouble(3) == 80.0

        when:
        int[] sel = new int[5]
        int matches = VectorizedMath.filterGreaterThan(vals, 25.0, sel) // Should match indices 1 (50.0), 3 (80.0), 4 (30.0)
        batch.setSelection(sel, matches)

        then:
        matches == 3
        batch.numRows() == 3
        batch.getActualRowIndex(0) == 1
        batch.getActualRowIndex(1) == 3
        batch.getActualRowIndex(2) == 4
    }

    def "should compute vectorized statistics accurately"() {
        given:
        def vals = new DoubleColumnVector([2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0] as double[])

        expect:
        VectorizedMath.sum(vals) == 40.0
        VectorizedMath.mean(vals) == 5.0
        VectorizedMath.min(vals) == 2.0
        VectorizedMath.max(vals) == 9.0
        Math.abs(VectorizedMath.variance(vals) - 4.57142857) < 1e-6
        Math.abs(VectorizedMath.stddev(vals) - 2.1380899) < 1e-6
    }

    def "should compute per-second rate derivative correctly"() {
        given:
        // Timestamps 10 seconds apart with counter increasing by 100 per step
        def ts = new TimestampColumnVector([0L, 10000L, 20000L] as long[])
        def vals = new DoubleColumnVector([1000.0, 1100.0, 1200.0] as double[])
        def rateOut = new DoubleColumnVector(2)

        when:
        VectorizedMath.rate(ts, vals, rateOut)

        then:
        rateOut.size() == 2
        // 100 delta / 10 sec = 10.0 per second
        rateOut.getDouble(0) == 10.0
        rateOut.getDouble(1) == 10.0
    }

    def "should compute EWMA smoothing correctly"() {
        given:
        def vals = new DoubleColumnVector([10.0, 20.0, 30.0] as double[])
        def out = new DoubleColumnVector(3)

        when:
        VectorizedMath.ewma(vals, 0.5, out)

        then:
        out.size() == 3
        out.getDouble(0) == 10.0
        out.getDouble(1) == 15.0 // 0.5 * 20 + 0.5 * 10 = 15
        out.getDouble(2) == 22.5 // 0.5 * 30 + 0.5 * 15 = 22.5
    }
}
