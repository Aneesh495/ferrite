package org.ferrite.bitstream

import groovy.transform.CompileStatic

/**
 * Zero-allocation MSB-first bitstream reader.
 * Decodes arbitrary-width bit sequences directly from byte slices without heap churn.
 */
@CompileStatic
class BitReader {
    private final byte[] buffer
    private final int byteOffset
    private final int byteLength
    private int currentByteIndex
    private int bitsInCurrentByte
    private int currentByteValue
    private long totalBitsRead

    BitReader(byte[] buffer) {
        this(buffer, 0, buffer != null ? buffer.length : 0)
    }

    BitReader(byte[] buffer, int offset, int length) {
        if (buffer == null) {
            throw new IllegalArgumentException("Buffer cannot be null")
        }
        if (offset < 0 || length < 0 || offset + length > buffer.length) {
            throw new IndexOutOfBoundsException("Invalid offset/length: offset=${offset}, length=${length}, capacity=${buffer.length}")
        }
        this.buffer = buffer
        this.byteOffset = offset
        this.byteLength = length
        this.currentByteIndex = offset
        this.bitsInCurrentByte = 0
        this.currentByteValue = 0
        this.totalBitsRead = 0L
    }

    int readBit() {
        if (bitsInCurrentByte == 0) {
            if (currentByteIndex >= byteOffset + byteLength) {
                throw new EOFException("End of bitstream reached at byte ${currentByteIndex}")
            }
            currentByteValue = buffer[currentByteIndex++] & 0xFF
            bitsInCurrentByte = 8
        }

        bitsInCurrentByte--
        totalBitsRead++
        (currentByteValue >>> bitsInCurrentByte) & 1
    }

    long readBits(int numBits) {
        if (numBits <= 0) return 0L
        if (numBits > 64) {
            throw new IllegalArgumentException("Cannot read more than 64 bits at once: ${numBits}")
        }

        long value = 0L
        for (int i = 0; i < numBits; i++) {
            value = (value << 1) | (long) readBit()
        }
        value
    }

    int readByte() {
        (int) (readBits(8) & 0xFFL)
    }

    int readInt() {
        (int) (readBits(32) & 0xFFFFFFFFL)
    }

    long readLong() {
        readBits(64)
    }

    boolean hasMoreBits() {
        bitsInCurrentByte > 0 || currentByteIndex < (byteOffset + byteLength)
    }

    long remainingBits() {
        long remainingBytes = Math.max(0, (byteOffset + byteLength) - currentByteIndex)
        remainingBytes * 8L + (long) bitsInCurrentByte
    }

    long getTotalBitsRead() {
        totalBitsRead
    }
}
