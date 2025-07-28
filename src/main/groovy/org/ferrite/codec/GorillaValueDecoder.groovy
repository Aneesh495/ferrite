package org.ferrite.codec

import groovy.transform.CompileStatic
import org.ferrite.bitstream.BitReader

/**
 * Facebook Gorilla floating-point XOR delta-of-delta decoder.
 * Reconstructs lossless 64-bit IEEE 754 floating point series from bitstream.
 */
@CompileStatic
class GorillaValueDecoder {
    private final BitReader reader
    private long prevBits
    private int prevLeading
    private int prevTrailing
    private int decodedCount

    GorillaValueDecoder(BitReader reader) {
        if (reader == null) {
            throw new IllegalArgumentException("BitReader cannot be null")
        }
        this.reader = reader
        this.prevBits = 0L
        this.prevLeading = -1
        this.prevTrailing = -1
        this.decodedCount = 0
    }

    double nextValue() {
        if (decodedCount == 0) {
            long bits = reader.readLong()
            prevBits = bits
            decodedCount++
            return Double.longBitsToDouble(bits)
        }

        int bit0 = reader.readBit()
        if (bit0 == 0) {
            // Value unchanged
            decodedCount++
            return Double.longBitsToDouble(prevBits)
        }

        int bit1 = reader.readBit()
        long xor
        if (bit1 == 0) {
            // Re-use previous leading and trailing window
            int length = 64 - prevLeading - prevTrailing
            long meaningful = reader.readBits(length)
            xor = meaningful << prevTrailing
        } else {
            // Read new leading and length window
            int leading = (int) reader.readBits(5)
            int length = (int) reader.readBits(6)
            long meaningful = reader.readBits(length)
            int trailing = 64 - leading - length
            xor = meaningful << trailing

            prevLeading = leading
            prevTrailing = trailing
        }

        long bits = prevBits ^ xor
        prevBits = bits
        decodedCount++
        Double.longBitsToDouble(bits)
    }

    double[] decodeAll(int count) {
        if (count <= 0) return new double[0]
        double[] result = new double[count]
        for (int i = 0; i < count; i++) {
            result[i] = nextValue()
        }
        result
    }

    int getDecodedCount() {
        decodedCount
    }
}
