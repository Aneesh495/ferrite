package org.ferrite.common

import groovy.transform.CompileStatic

import java.nio.ByteBuffer

/**
 * Castagnoli CRC32C (polynomial 0x82F63B78) implementation.
 * Leverages JVM hardware intrinsic acceleration with software table-driven fallback.
 */
@CompileStatic
class CRC32C {
    private static final int[] TABLE = new int[256]

    static {
        int poly = (int) 0x82F63B78L
        for (int i = 0; i < 256; i++) {
            int crc = i
            for (int j = 0; j < 8; j++) {
                if ((crc & 1) != 0) {
                    crc = (crc >>> 1) ^ poly
                } else {
                    crc >>>= 1
                }
            }
            TABLE[i] = crc
        }
    }

    static int compute(byte[] data) {
        if (data == null) return 0
        compute(data, 0, data.length)
    }

    static int compute(byte[] data, int offset, int length) {
        if (data == null || length <= 0) return 0
        try {
            java.util.zip.CRC32C crc32c = new java.util.zip.CRC32C()
            crc32c.update(data, offset, length)
            return (int) crc32c.getValue()
        } catch (Throwable ignored) {
            return computeSoftware(data, offset, length)
        }
    }

    static int compute(ByteBuffer buffer, int offset, int length) {
        if (buffer == null || length <= 0) return 0
        try {
            java.util.zip.CRC32C crc32c = new java.util.zip.CRC32C()
            int originalPos = buffer.position()
            int originalLimit = buffer.limit()
            buffer.position(offset)
            buffer.limit(offset + length)
            crc32c.update(buffer)
            buffer.position(originalPos)
            buffer.limit(originalLimit)
            return (int) crc32c.getValue()
        } catch (Throwable ignored) {
            byte[] tmp = new byte[length]
            int pos = buffer.position()
            buffer.position(offset)
            buffer.get(tmp, 0, length)
            buffer.position(pos)
            return computeSoftware(tmp, 0, length)
        }
    }

    static int computeSoftware(byte[] data, int offset, int length) {
        int crc = ~0
        int end = offset + length
        for (int i = offset; i < end; i++) {
            int index = (crc ^ data[i]) & 0xFF
            crc = (crc >>> 8) ^ TABLE[index]
        }
        ~crc
    }

    static int mask(int crc) {
        // Rotates and masks CRC to prevent payload matching CRC value
        (int) (((crc >>> 15) | (crc << 17)) + 0xa282ead8L)
    }

    static int unmask(int maskedCrc) {
        int rot = (int) (maskedCrc - 0xa282ead8L)
        ((rot >>> 17) | (rot << 15))
    }
}
