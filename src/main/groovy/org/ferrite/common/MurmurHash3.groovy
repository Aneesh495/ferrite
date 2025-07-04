package org.ferrite.common

import groovy.transform.CompileStatic
import java.nio.charset.StandardCharsets

/**
 * High-performance 32-bit and 64/128-bit MurmurHash3 implementation.
 * Used for bloom filters, HyperLogLog registers, and consistent hash rings.
 */
@CompileStatic
class MurmurHash3 {
    private static final int C1_32 = (int) 0xcc9e2d51L
    private static final int C2_32 = 0x1b873593

    private static final long C1_128 = 0x87c37b91114253d5L
    private static final long C2_128 = 0x4cf5ad432745937fL

    static int hash32(byte[] data, int offset, int length, int seed) {
        int h1 = seed
        int nblocks = length >> 2

        for (int i = 0; i < nblocks; i++) {
            int index = offset + (i << 2)
            int k1 = (data[index] & 0xFF) |
                    ((data[index + 1] & 0xFF) << 8) |
                    ((data[index + 2] & 0xFF) << 16) |
                    ((data[index + 3] & 0xFF) << 24)

            k1 *= C1_32
            k1 = (k1 << 15) | (k1 >>> 17)
            k1 *= C2_32

            h1 ^= k1
            h1 = (h1 << 13) | (h1 >>> 19)
            h1 = h1 * 5 + (int) 0xe6546b64L
        }

        int tailIndex = offset + (nblocks << 2)
        int k1 = 0
        int tailLength = length & 3

        if (tailLength == 3) {
            k1 ^= (data[tailIndex + 2] & 0xFF) << 16
        }
        if (tailLength >= 2) {
            k1 ^= (data[tailIndex + 1] & 0xFF) << 8
        }
        if (tailLength >= 1) {
            k1 ^= (data[tailIndex] & 0xFF)
            k1 *= C1_32
            k1 = (k1 << 15) | (k1 >>> 17)
            k1 *= C2_32
            h1 ^= k1
        }

        h1 ^= length
        fmix32(h1)
    }

    static int hash32(String string) {
        byte[] bytes = string.getBytes(StandardCharsets.UTF_8)
        hash32(bytes, 0, bytes.length, 0)
    }

    static long hash64(byte[] data, int offset, int length, long seed) {
        long[] h = hash128(data, offset, length, seed)
        h[0]
    }

    static long hash64(String string) {
        byte[] bytes = string.getBytes(StandardCharsets.UTF_8)
        hash64(bytes, 0, bytes.length, 0L)
    }

    static long[] hash128(byte[] data, int offset, int length, long seed) {
        long h1 = seed
        long h2 = seed

        int nblocks = length >> 4

        for (int i = 0; i < nblocks; i++) {
            int idx = offset + (i << 4)
            long k1 = getLongLittleEndian(data, idx)
            long k2 = getLongLittleEndian(data, idx + 8)

            k1 *= C1_128
            k1 = Long.rotateLeft(k1, 31)
            k1 *= C2_128
            h1 ^= k1

            h1 = Long.rotateLeft(h1, 27)
            h1 += h2
            h1 = h1 * 5L + 0x52dce729L

            k2 *= C2_128
            k2 = Long.rotateLeft(k2, 33)
            k2 *= C1_128
            h2 ^= k2

            h2 = Long.rotateLeft(h2, 31)
            h2 += h1
            h2 = h2 * 5L + 0x38495ab5L
        }

        int tail = offset + (nblocks << 4)
        long k1 = 0
        long k2 = 0
        int tailLen = length & 15

        switch (tailLen) {
            case 15: k2 ^= ((long) data[tail + 14] & 0xFFL) << 48
            case 14: k2 ^= ((long) data[tail + 13] & 0xFFL) << 40
            case 13: k2 ^= ((long) data[tail + 12] & 0xFFL) << 32
            case 12: k2 ^= ((long) data[tail + 11] & 0xFFL) << 24
            case 11: k2 ^= ((long) data[tail + 10] & 0xFFL) << 16
            case 10: k2 ^= ((long) data[tail + 9] & 0xFFL) << 8
            case 9:
                k2 ^= ((long) data[tail + 8] & 0xFFL)
                k2 *= C2_128
                k2 = Long.rotateLeft(k2, 33)
                k2 *= C1_128
                h2 ^= k2
            case 8: k1 ^= ((long) data[tail + 7] & 0xFFL) << 56
            case 7: k1 ^= ((long) data[tail + 6] & 0xFFL) << 48
            case 6: k1 ^= ((long) data[tail + 5] & 0xFFL) << 40
            case 5: k1 ^= ((long) data[tail + 4] & 0xFFL) << 32
            case 4: k1 ^= ((long) data[tail + 3] & 0xFFL) << 24
            case 3: k1 ^= ((long) data[tail + 2] & 0xFFL) << 16
            case 2: k1 ^= ((long) data[tail + 1] & 0xFFL) << 8
            case 1:
                k1 ^= ((long) data[tail] & 0xFFL)
                k1 *= C1_128
                k1 = Long.rotateLeft(k1, 31)
                k1 *= C2_128
                h1 ^= k1
        }

        h1 ^= (long) length
        h2 ^= (long) length

        h1 += h2
        h2 += h1

        h1 = fmix64(h1)
        h2 = fmix64(h2)

        h1 += h2
        h2 += h1

        [h1, h2] as long[]
    }

    private static long getLongLittleEndian(byte[] data, int offset) {
        return ((long) data[offset] & 0xFFL) |
                (((long) data[offset + 1] & 0xFFL) << 8) |
                (((long) data[offset + 2] & 0xFFL) << 16) |
                (((long) data[offset + 3] & 0xFFL) << 24) |
                (((long) data[offset + 4] & 0xFFL) << 32) |
                (((long) data[offset + 5] & 0xFFL) << 40) |
                (((long) data[offset + 6] & 0xFFL) << 48) |
                (((long) data[offset + 7] & 0xFFL) << 56)
    }

    private static int fmix32(int h) {
        h ^= h >>> 16
        h *= (int) 0x85ebca6bL
        h ^= h >>> 13
        h *= (int) 0xc2b2ae35L
        h ^= h >>> 16
        h
    }

    private static long fmix64(long k) {
        k ^= k >>> 33
        k *= 0xff51afd7ed558ccdL
        k ^= k >>> 33
        k *= 0xc4ceb9fe1a85ec53L
        k ^= k >>> 33
        k
    }
}
