package org.ferrite.codec

import groovy.transform.CompileStatic
import org.ferrite.bitstream.BitWriter

/**
 * Facebook Gorilla floating-point XOR delta-of-delta encoder.
 * Encodes IEEE 754 64-bit floating point numbers by exploiting the fact that adjacent
 * samples in a time series typically share significant high-order mantissa and exponent bits.
 */
@CompileStatic
class GorillaValueEncoder {
    private final BitWriter writer
    private long prevBits
    private int prevLeading
    private int prevTrailing
    private int count

    GorillaValueEncoder(BitWriter writer) {
        if (writer == null) {
            throw new IllegalArgumentException("BitWriter cannot be null")
        }
        this.writer = writer
        this.prevBits = 0L
        this.prevLeading = -1
        this.prevTrailing = -1
        this.count = 0
    }

    void encode(double value) {
        long bits = Double.doubleToRawLongBits(value)

        if (count == 0) {
            writer.writeLong(bits)
            prevBits = bits
            count++
            return
        }

        long xor = bits ^ prevBits

        if (xor == 0L) {
            // Case XOR == 0: value unchanged, write bit '0'
            writer.writeBitZero()
        } else {
            // Value changed, write bit '1'
            writer.writeBitOne()

            int leading = Long.numberOfLeadingZeros(xor)
            int trailing = Long.numberOfTrailingZeros(xor)

            // Cap leading zeros at 31 since we allocate 5 bits (0..31)
            if (leading >= 32) {
                leading = 31
            }

            if (prevLeading != -1 && leading >= prevLeading && trailing >= prevTrailing) {
                // Bits fall within previous leading/trailing window: write bit '0'
                writer.writeBitZero()
                int length = 64 - prevLeading - prevTrailing
                long meaningful = xor >>> prevTrailing
                writer.writeBits(meaningful, length)
            } else {
                // New window required: write bit '1'
                writer.writeBitOne()
                int length = 64 - leading - trailing
                writer.writeBits((long) leading, 5)
                // Encode length - 1 so lengths 1..64 fit in 6 bits (0..63)
                writer.writeBits((long) (length - 1), 6)
                long meaningful = xor >>> trailing
                writer.writeBits(meaningful, length)

                prevLeading = leading
                prevTrailing = trailing
            }
        }

        prevBits = bits
        count++
    }

    void flush() {
        writer.flush()
    }

    int getCount() {
        count
    }
}
