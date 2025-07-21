package org.ferrite.codec

import groovy.transform.CompileStatic
import org.ferrite.bitstream.BitReader

/**
 * Facebook Gorilla timestamp delta-of-delta decoder.
 * Reconstructs original monotonic timestamp sequence from compressed bitstream.
 */
@CompileStatic
class GorillaTimestampDecoder {
    private final BitReader reader
    private long prevTimestamp
    private long prevDelta
    private int decodedCount

    GorillaTimestampDecoder(BitReader reader) {
        if (reader == null) {
            throw new IllegalArgumentException("BitReader cannot be null")
        }
        this.reader = reader
        this.prevTimestamp = 0L
        this.prevDelta = 0L
        this.decodedCount = 0
    }

    long nextTimestamp() {
        if (decodedCount == 0) {
            long first = reader.readLong()
            prevTimestamp = first
            decodedCount++
            return first
        }

        if (decodedCount == 1) {
            long delta = (long) reader.readInt()
            prevDelta = delta
            prevTimestamp += delta
            decodedCount++
            return prevTimestamp
        }

        long dod
        int bit0 = reader.readBit()
        if (bit0 == 0) {
            dod = 0L
        } else {
            int bit1 = reader.readBit()
            if (bit1 == 0) {
                // '10' -> 7 bits
                long bits = reader.readBits(7)
                dod = bits - 63L
            } else {
                int bit2 = reader.readBit()
                if (bit2 == 0) {
                    // '110' -> 9 bits
                    long bits = reader.readBits(9)
                    dod = bits - 255L
                } else {
                    int bit3 = reader.readBit()
                    if (bit3 == 0) {
                        // '1110' -> 12 bits
                        long bits = reader.readBits(12)
                        dod = bits - 2047L
                    } else {
                        // '1111' -> 32 bits
                        dod = (long) reader.readInt()
                    }
                }
            }
        }

        prevDelta += dod
        prevTimestamp += prevDelta
        decodedCount++
        prevTimestamp
    }

    long[] decodeAll(int count) {
        if (count <= 0) return new long[0]
        long[] result = new long[count]
        for (int i = 0; i < count; i++) {
            result[i] = nextTimestamp()
        }
        result
    }

    int getDecodedCount() {
        decodedCount
    }
}
