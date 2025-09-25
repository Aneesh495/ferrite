package org.ferrite.analytics

import groovy.transform.CompileStatic
import org.ferrite.common.MurmurHash3

/**
 * 64-bit HyperLogLog cardinality estimator with linear counting and large-range bias corrections.
 * Estimates unique metric series counts with standard error <= 0.81% in 16KB of memory.
 */
@CompileStatic
class HyperLogLog implements Serializable {
    private static final long serialVersionUID = 1L
    private static final int DEFAULT_P = 14 // 16384 registers, ~0.81% error

    private final int p
    private final int m
    private final double alphaMM
    private final byte[] registers

    HyperLogLog() {
        this(DEFAULT_P)
    }

    HyperLogLog(int p) {
        if (p < 4 || p > 18) {
            throw new IllegalArgumentException("Precision p must be between 4 and 18: ${p}")
        }
        this.p = p
        this.m = 1 << p
        this.registers = new byte[m]
        this.alphaMM = calculateAlpha(m) * (double) m * (double) m
    }

    void offer(String value) {
        if (value == null) return
        long hash = MurmurHash3.hash64(value)
        offerHash(hash)
    }

    void offer(long value) {
        byte[] bytes = new byte[8]
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (value & 0xFFL)
            value >>>= 8
        }
        long hash = MurmurHash3.hash64(bytes, 0, 8, 0L)
        offerHash(hash)
    }

    void offerHash(long hash) {
        int j = (int) (hash >>> (64 - p))
        long w = hash << p
        int leadingZeros = Long.numberOfLeadingZeros(w) + 1
        if (leadingZeros > 64 - p) {
            leadingZeros = 64 - p
        }

        byte current = registers[j]
        if (leadingZeros > (current & 0xFF)) {
            registers[j] = (byte) leadingZeros
        }
    }

    long cardinality() {
        double sum = 0.0d
        int zeros = 0
        for (int i = 0; i < m; i++) {
            int val = registers[i] & 0xFF
            sum += 1.0d / (double) (1L << val)
            if (val == 0) {
                zeros++
            }
        }

        double estimate = alphaMM / sum

        // Small range correction (Linear Counting)
        if (estimate <= 2.5d * (double) m) {
            if (zeros > 0) {
                estimate = (double) m * Math.log((double) m / (double) zeros)
            }
        } else if (estimate > (1.0d / 30.0d) * Math.pow(2.0d, 64.0d)) {
            // Large range 64-bit correction
            estimate = -Math.pow(2.0d, 64.0d) * Math.log(1.0d - (estimate / Math.pow(2.0d, 64.0d)))
        }

        (long) Math.round(estimate)
    }

    void merge(HyperLogLog other) {
        if (other == null || other.p != this.p) {
            throw new IllegalArgumentException("Cannot merge HyperLogLog with different precision")
        }
        for (int i = 0; i < m; i++) {
            int current = registers[i] & 0xFF
            int otherVal = other.registers[i] & 0xFF
            if (otherVal > current) {
                registers[i] = (byte) otherVal
            }
        }
    }

    private static double calculateAlpha(int m) {
        switch (m) {
            case 16: return 0.673d
            case 32: return 0.697d
            case 64: return 0.709d
            default: return 0.7213d / (1.0d + 1.079d / (double) m)
        }
    }
}
