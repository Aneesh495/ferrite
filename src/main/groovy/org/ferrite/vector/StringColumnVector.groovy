package org.ferrite.vector

import groovy.transform.CompileStatic
import org.ferrite.common.ValueType

import java.nio.charset.StandardCharsets
import java.util.Arrays

/**
 * Variable-length UTF-8 string vector packed into Apache Arrow style offsets and byte buffers.
 */
@CompileStatic
class StringColumnVector implements ColumnVector {
    private byte[] data
    private int[] offsets
    private long[] nullBitmap
    private int size
    private int dataOffset

    StringColumnVector(int capacity) {
        this.offsets = new int[capacity + 1]
        this.data = new byte[capacity * 16]
        int bitmapSize = (capacity + 63) >>> 6
        this.nullBitmap = new long[bitmapSize]
        Arrays.fill(this.nullBitmap, -1L)
        this.size = 0
        this.dataOffset = 0
        this.offsets[0] = 0
    }

    void append(String str) {
        ensureCapacity(size + 1)
        if (str == null) {
            setNullAt(size)
            offsets[size + 1] = dataOffset
            size++
            return
        }

        byte[] bytes = str.getBytes(StandardCharsets.UTF_8)
        ensureDataCapacity(dataOffset + bytes.length)
        System.arraycopy(bytes, 0, data, dataOffset, bytes.length)
        dataOffset += bytes.length
        offsets[size + 1] = dataOffset
        size++
    }

    String getString(int row) {
        if (isNullAt(row)) return null
        int start = offsets[row]
        int len = offsets[row + 1] - start
        new String(data, start, len, StandardCharsets.UTF_8)
    }

    @Override
    int size() {
        size
    }

    @Override
    ValueType getType() {
        ValueType.STRING
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
        if (minCap >= offsets.length) {
            int newCap = Math.max(offsets.length * 2, minCap + 1)
            int[] newOffsets = new int[newCap]
            System.arraycopy(offsets, 0, newOffsets, 0, size + 1)
            this.offsets = newOffsets

            int newBitmapSize = (newCap + 63) >>> 6
            long[] newBitmap = new long[newBitmapSize]
            Arrays.fill(newBitmap, -1L)
            System.arraycopy(nullBitmap, 0, newBitmap, 0, nullBitmap.length)
            this.nullBitmap = newBitmap
        }
    }

    private void ensureDataCapacity(int minCap) {
        if (minCap > data.length) {
            int newCap = Math.max(data.length * 2, minCap)
            byte[] newData = new byte[newCap]
            System.arraycopy(data, 0, newData, 0, dataOffset)
            this.data = newData
        }
    }
}
