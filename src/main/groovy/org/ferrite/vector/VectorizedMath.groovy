package org.ferrite.vector

import groovy.transform.CompileStatic

/**
 * High-performance primitive vectorized math kernels.
 * Implements cache-aligned SIMD-friendly linear algebra, filters, rate derivations, and EWMA.
 */
@CompileStatic
class VectorizedMath {

    static void add(DoubleColumnVector a, DoubleColumnVector b, DoubleColumnVector out) {
        int n = Math.min(a.size(), b.size())
        out.setSize(n)
        double[] aData = a.rawData()
        double[] bData = b.rawData()
        double[] outData = out.rawData()

        for (int i = 0; i < n; i++) {
            outData[i] = aData[i] + bData[i]
        }
    }

    static void subtract(DoubleColumnVector a, DoubleColumnVector b, DoubleColumnVector out) {
        int n = Math.min(a.size(), b.size())
        out.setSize(n)
        double[] aData = a.rawData()
        double[] bData = b.rawData()
        double[] outData = out.rawData()

        for (int i = 0; i < n; i++) {
            outData[i] = aData[i] - bData[i]
        }
    }

    static void multiplyScalar(DoubleColumnVector a, double scalar, DoubleColumnVector out) {
        int n = a.size()
        out.setSize(n)
        double[] aData = a.rawData()
        double[] outData = out.rawData()

        for (int i = 0; i < n; i++) {
            outData[i] = aData[i] * scalar
        }
    }

    static double sum(DoubleColumnVector vec) {
        double total = 0.0
        double[] data = vec.rawData()
        int n = vec.size()
        for (int i = 0; i < n; i++) {
            total += data[i]
        }
        total
    }

    static double mean(DoubleColumnVector vec) {
        int n = vec.size()
        if (n == 0) return 0.0
        sum(vec) / (double) n
    }

    static double min(DoubleColumnVector vec) {
        int n = vec.size()
        if (n == 0) return 0.0
        double[] data = vec.rawData()
        double m = data[0]
        for (int i = 1; i < n; i++) {
            if (data[i] < m) m = data[i]
        }
        m
    }

    static double max(DoubleColumnVector vec) {
        int n = vec.size()
        if (n == 0) return 0.0
        double[] data = vec.rawData()
        double m = data[0]
        for (int i = 1; i < n; i++) {
            if (data[i] > m) m = data[i]
        }
        m
    }

    static double variance(DoubleColumnVector vec) {
        int n = vec.size()
        if (n < 2) return 0.0
        double m = mean(vec)
        double[] data = vec.rawData()
        double sumSq = 0.0
        for (int i = 0; i < n; i++) {
            double diff = data[i] - m
            sumSq += diff * diff
        }
        sumSq / (double) (n - 1)
    }

    static double stddev(DoubleColumnVector vec) {
        Math.sqrt(variance(vec))
    }

    static int filterGreaterThan(DoubleColumnVector vec, double threshold, int[] outSelection) {
        double[] data = vec.rawData()
        int n = vec.size()
        int count = 0
        for (int i = 0; i < n; i++) {
            if (data[i] > threshold) {
                outSelection[count++] = i
            }
        }
        count
    }

    static int filterInRange(TimestampColumnVector ts, long fromEpochMs, long toEpochMs, int[] outSelection) {
        long[] data = ts.rawData()
        int n = ts.size()
        int count = 0
        for (int i = 0; i < n; i++) {
            long t = data[i]
            if (t >= fromEpochMs && t <= toEpochMs) {
                outSelection[count++] = i
            }
        }
        count
    }

    static void rate(TimestampColumnVector ts, DoubleColumnVector val, DoubleColumnVector out) {
        int n = Math.min(ts.size(), val.size())
        if (n < 2) {
            out.setSize(0)
            return
        }

        out.setSize(n - 1)
        long[] tData = ts.rawData()
        double[] vData = val.rawData()
        double[] oData = out.rawData()

        for (int i = 1; i < n; i++) {
            long dtMs = tData[i] - tData[i - 1]
            if (dtMs > 0L) {
                double dtSec = (double) dtMs / 1000.0
                double dv = vData[i] - vData[i - 1]
                oData[i - 1] = dv / dtSec
            } else {
                oData[i - 1] = 0.0
            }
        }
    }

    static void ewma(DoubleColumnVector val, double alpha, DoubleColumnVector out) {
        int n = val.size()
        if (n == 0) {
            out.setSize(0)
            return
        }

        out.setSize(n)
        double[] vData = val.rawData()
        double[] oData = out.rawData()

        oData[0] = vData[0]
        double oneMinusAlpha = 1.0 - alpha

        for (int i = 1; i < n; i++) {
            oData[i] = alpha * vData[i] + oneMinusAlpha * oData[i - 1]
        }
    }

    private VectorizedMath() {}
}
