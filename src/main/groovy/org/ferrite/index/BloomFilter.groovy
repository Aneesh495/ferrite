package org.ferrite.index

import groovy.transform.CompileStatic
import org.ferrite.bitstream.VarInt
import org.ferrite.common.ByteBuf
import org.ferrite.common.MurmurHash3

import java.nio.charset.StandardCharsets

/**
 * Probabilistic membership filter with optimal bit allocations and Kirsch-Mitzenmacher double-hashing.
 * Deployed in segment headers and series indexes for O(1) existence checks.
 */
@CompileStatic
class BloomFilter implements Serializable {
    private static final long serialVersionUID = 1L

    private final long numBits
    private final int numHashFunctions
    private final long[] bitset
    private int elementCount

    BloomFilter(int expectedInsertions, double fpp) {
        if (expectedInsertions <= 0) {
            throw new IllegalArgumentException("expectedInsertions must be > 0: ${expectedInsertions}")
        }
        if (fpp <= 0.0 || fpp >= 1.0) {
            throw new IllegalArgumentException("fpp must be between 0 and 1: ${fpp}")
        }

        this.numBits = optimalNumOfBits(expectedInsertions, fpp)
        this.numHashFunctions = optimalNumOfHashFunctions(expectedInsertions, numBits)
        int numLongs = (int) ((numBits + 63L) >>> 6)
        this.bitset = new long[numLongs]
        this.elementCount = 0
    }

    private BloomFilter(long numBits, int numHashFunctions, long[] bitset, int elementCount) {
        this.numBits = numBits
        this.numHashFunctions = numHashFunctions
        this.bitset = bitset
        this.elementCount = elementCount
    }

    void add(byte[] data) {
        if (data == null) return
        long[] hashes = MurmurHash3.hash128(data, 0, data.length, 0L)
        long h1 = hashes[0]
        long h2 = hashes[1]

        long combinedHash = h1
        for (int i = 0; i < numHashFunctions; i++) {
            long bitIndex = (combinedHash & Long.MAX_VALUE) % numBits
            setBit(bitIndex)
            combinedHash += h2
        }
        elementCount++
    }

    void add(String text) {
        if (text == null) return
        add(text.getBytes(StandardCharsets.UTF_8))
    }

    void add(long value) {
        byte[] bytes = new byte[8]
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (value & 0xFFL)
            value >>>= 8
        }
        add(bytes)
    }

    boolean mightContain(byte[] data) {
        if (data == null) return false
        long[] hashes = MurmurHash3.hash128(data, 0, data.length, 0L)
        long h1 = hashes[0]
        long h2 = hashes[1]

        long combinedHash = h1
        for (int i = 0; i < numHashFunctions; i++) {
            long bitIndex = (combinedHash & Long.MAX_VALUE) % numBits
            if (!getBit(bitIndex)) {
                return false
            }
            combinedHash += h2
        }
        true
    }

    boolean mightContain(String text) {
        if (text == null) return false
        mightContain(text.getBytes(StandardCharsets.UTF_8))
    }

    boolean mightContain(long value) {
        byte[] bytes = new byte[8]
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (value & 0xFFL)
            value >>>= 8
        }
        mightContain(bytes)
    }

    int getElementCount() {
        elementCount
    }

    long getNumBits() {
        numBits
    }

    int getNumHashFunctions() {
        numHashFunctions
    }

    void writeTo(ByteBuf buf) {
        buf.writeLong(numBits)
        VarInt.writeVarInt(buf, numHashFunctions)
        VarInt.writeVarInt(buf, elementCount)
        VarInt.writeVarInt(buf, bitset.length)
        for (int i = 0; i < bitset.length; i++) {
            buf.writeLong(bitset[i])
        }
    }

    static BloomFilter readFrom(ByteBuf buf) {
        long numBits = buf.readLong()
        int numHashFunctions = VarInt.readVarInt(buf)
        int elementCount = VarInt.readVarInt(buf)
        int len = VarInt.readVarInt(buf)
        long[] bitset = new long[len]
        for (int i = 0; i < len; i++) {
            bitset[i] = buf.readLong()
        }
        new BloomFilter(numBits, numHashFunctions, bitset, elementCount)
    }

    private void setBit(long index) {
        int wordIndex = (int) (index >>> 6)
        bitset[wordIndex] |= (1L << (int) (index & 0x3FL))
    }

    private boolean getBit(long index) {
        int wordIndex = (int) (index >>> 6)
        (bitset[wordIndex] & (1L << (int) (index & 0x3FL))) != 0L
    }

    private static long optimalNumOfBits(long n, double p) {
        (long) (-n * Math.log(p) / (Math.log(2.0) * Math.log(2.0)))
    }

    private static int optimalNumOfHashFunctions(long n, long m) {
        Math.max(1, (int) Math.round(((double) m / (double) n) * Math.log(2.0)))
    }
}
