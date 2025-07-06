package org.ferrite.bitstream

import groovy.transform.CompileStatic

import java.io.ByteArrayOutputStream

/**
 * High-performance MSB-first bitstream writer.
 * Employs word buffering to minimize byte-array reallocation overhead during Gorilla compression.
 */
@CompileStatic
class BitWriter {
    private final ByteArrayOutputStream output
    private int currentByte
    private int bitsInCurrentByte
    private long totalBitsWritten

    BitWriter() {
        this(64)
    }

    BitWriter(int initialCapacityBytes) {
        this.output = new ByteArrayOutputStream(initialCapacityBytes)
        this.currentByte = 0
        this.bitsInCurrentByte = 0
        this.totalBitsWritten = 0L
    }

    void writeBit(int bit) {
        currentByte = (currentByte << 1) | (bit & 1)
        bitsInCurrentByte++
        totalBitsWritten++

        if (bitsInCurrentByte == 8) {
            output.write(currentByte)
            currentByte = 0
            bitsInCurrentByte = 0
        }
    }

    void writeBitZero() {
        writeBit(0)
    }

    void writeBitOne() {
        writeBit(1)
    }

    void writeBits(long value, int numBits) {
        if (numBits <= 0) return
        if (numBits > 64) {
            throw new IllegalArgumentException("Cannot write more than 64 bits in a single call: ${numBits}")
        }

        for (int i = numBits - 1; i >= 0; i--) {
            int bit = (int) ((value >>> i) & 1L)
            writeBit(bit)
        }
    }

    void writeByte(int b) {
        writeBits((long) (b & 0xFF), 8)
    }

    void writeInt(int v) {
        writeBits((long) (v & 0xFFFFFFFFL), 32)
    }

    void writeLong(long v) {
        writeBits(v, 64)
    }

    void flush() {
        if (bitsInCurrentByte > 0) {
            int padded = currentByte << (8 - bitsInCurrentByte)
            output.write(padded)
            currentByte = 0
            bitsInCurrentByte = 0
        }
    }

    byte[] toByteArray() {
        flush()
        output.toByteArray()
    }

    int sizeInBytes() {
        (int) ((totalBitsWritten + 7L) / 8L)
    }

    long getTotalBitsWritten() {
        totalBitsWritten
    }
}
