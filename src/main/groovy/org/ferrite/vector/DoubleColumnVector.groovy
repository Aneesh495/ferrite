package org.ferrite.vector

import groovy.transform.CompileStatic
import org.ferrite.common.ValueType

import java.util.Arrays

/**
 * Contiguous primitive 64-bit IEEE 754 floating point vector with bitmask validity flags.
 */
@CompileStatic
class DoubleColumnVector implements ColumnVector {
    private double[] data
    private long[] nullBitmap
    private int size

    DoubleColumnVector(int capacity) {
        this.data = new double[capacity]
        int bitmapSize = (capacity + 63) >>> 6
        this.nullBitmap = new long[bitmapSize]
        Arrays.fill(this.nullBitmap, -1L) // All valid by default
        this.size = 0
    }

    DoubleColumnVector(double[] values) {
        this.data = values
        this.size = values.length
        int bitmapSize = (size + 63) >>> 6
        this.nullBitmap = new long[bitmapSize]
        Arrays.fill(this.nullBitmap, -1L)
    }

    void append(double val) {
        ensureCapacity(size + 1)
        data[size++] = val
    }

    double getDouble(int row) {
        data[row]
    }

    void setDouble(int row, double val) {
        data[row] = val
    }

    double[] rawData() {
        data
    }

    @Override
    int size() {
        size
    }

    void setSize(int s) {
        this.size = s
    }

    @Override
    ValueType getType() {
        ValueType.DOUBLE
    }

    @Override
    boolean isNullAt(int row) {
        int word = row >>> 6
        int bit = row & 0x3F
        (nullBitmap[word] & (1L << bit)) == 0L
    }

    @Override
    void setNullAt(int row) {
        int word = row >>> 6
        int bit = row & 0x3F
        nullBitmap[word] &= ~(1L << bit)
    }

    private void ensureCapacity(int minCap) {
        if (minCap > data.length) {
            int newCap = Math.max(data.length * 2, minCap)
            double[] newData = new double[newCap]
            System.arraycopy(data, 0, newData, 0, size)
            this.data = newData

            int newBitmapSize = (newCap + 63) >>> 6
            long[] newBitmap = new long[newBitmapSize]
            Arrays.fill(newBitmap, -1L)
            System.arraycopy(nullBitmap, 0, newBitmap, 0, nullBitmap.length)
            this.nullBitmap = newBitmap
        }
    }
}
