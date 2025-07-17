package org.ferrite.codec

import groovy.transform.CompileStatic
import org.ferrite.bitstream.BitWriter

/**
 * Facebook Gorilla timestamp delta-of-delta encoder.
 * Encodes sequence of monotonic timestamps into a high-density bitstream.
 * Periodic series with constant intervals compress down to 1 bit per sample.
 */
@CompileStatic
class GorillaTimestampEncoder {
    private final BitWriter writer
    private long firstTimestamp
    private long prevTimestamp
    private long prevDelta
    private int count

    GorillaTimestampEncoder(BitWriter writer) {
        if (writer == null) {
            throw new IllegalArgumentException("BitWriter cannot be null")
        }
        this.writer = writer
        this.firstTimestamp = 0L
        this.prevTimestamp = 0L
        this.prevDelta = 0L
        this.count = 0
    }

    void encode(long timestamp) {
        if (count == 0) {
            firstTimestamp = timestamp
            prevTimestamp = timestamp
            writer.writeLong(timestamp)
            count++
            return
        }

        if (count == 1) {
            long delta = timestamp - prevTimestamp
            prevDelta = delta
            prevTimestamp = timestamp
            // Write first delta as 32-bit integer
            writer.writeInt((int) (delta & 0xFFFFFFFFL))
            count++
            return
        }

        long currentDelta = timestamp - prevTimestamp
        long dod = currentDelta - prevDelta
        prevDelta = currentDelta
        prevTimestamp = timestamp

        if (dod == 0L) {
            // Case D == 0: 1 bit '0'
            writer.writeBitZero()
        } else if (dod >= -63L && dod <= 64L) {
            // Case [-63, 64]: '10' followed by 7 bits
            writer.writeBits(0b10L, 2)
            writer.writeBits(dod + 63L, 7)
        } else if (dod >= -255L && dod <= 256L) {
            // Case [-255, 256]: '110' followed by 9 bits
            writer.writeBits(0b110L, 3)
            writer.writeBits(dod + 255L, 9)
        } else if (dod >= -2047L && dod <= 2048L) {
            // Case [-2047, 2048]: '1110' followed by 12 bits
            writer.writeBits(0b1110L, 4)
            writer.writeBits(dod + 2047L, 12)
        } else {
            // Case full 32-bit DOD: '1111' followed by 32 bits
            writer.writeBits(0b1111L, 4)
            writer.writeInt((int) (dod & 0xFFFFFFFFL))
        }
        count++
    }

    void flush() {
        writer.flush()
    }

    int getCount() {
        count
    }

    long getFirstTimestamp() {
        firstTimestamp
    }
}
