package org.ferrite.codec

import groovy.transform.CompileStatic
import org.ferrite.bitstream.VarInt
import org.ferrite.common.ByteBuf

import java.util.Arrays

/**
 * Pure-JVM LZ77 byte-level block compressor inspired by Snappy and LZ4.
 * Provides high-throughput compression for index blocks and string columns without native dependencies.
 */
@CompileStatic
class SnappyCodec {
    private static final int HASH_TABLE_SIZE = 4096
    private static final int MAX_OFFSET = 65535
    private static final int MIN_MATCH = 4
    private static final int MAX_COPY_LEN = 64 + MIN_MATCH - 1

    static byte[] compress(byte[] input) {
        if (input == null || input.length == 0) {
            return new byte[0]
        }

        ByteBuf out = ByteBuf.allocate(input.length + 32)
        VarInt.writeVarInt(out, input.length)

        int[] hashTable = new int[HASH_TABLE_SIZE]
        Arrays.fill(hashTable, -1)

        int ip = 0
        int anchor = 0
        int limit = input.length - MIN_MATCH

        while (ip < limit) {
            int h = hash4(input, ip)
            int ref = hashTable[h]
            hashTable[h] = ip

            if (ref != -1 && (ip - ref) <= MAX_OFFSET && match4(input, ip, ref)) {
                // Emit literal run before match
                int litLen = ip - anchor
                emitLiteral(out, input, anchor, litLen)

                // Find match length capped at MAX_COPY_LEN
                int matchLen = MIN_MATCH
                while (ip + matchLen < input.length && input[ip + matchLen] == input[ref + matchLen] && matchLen < MAX_COPY_LEN) {
                    matchLen++
                }

                // Emit copy tag: offset and length
                int offset = ip - ref
                emitCopy(out, offset, matchLen)

                ip += matchLen
                anchor = ip
            } else {
                ip++
            }
        }

        // Emit trailing literals
        if (anchor < input.length) {
            emitLiteral(out, input, anchor, input.length - anchor)
        }

        out.toByteArray()
    }

    static byte[] decompress(byte[] compressed) {
        if (compressed == null || compressed.length == 0) {
            return new byte[0]
        }

        ByteBuf inBuf = ByteBuf.wrap(compressed)
        int uncompressedLength = VarInt.readVarInt(inBuf)
        byte[] output = new byte[uncompressedLength]
        int op = 0

        while (inBuf.isReadable() && op < uncompressedLength) {
            int tag = inBuf.readUnsignedByte()
            int tagType = tag & 0x03

            if (tagType == 0) {
                // Literal
                int rawLen = (tag >>> 2) & 0x3F
                int len
                if (rawLen < 60) {
                    len = rawLen + 1
                } else if (rawLen == 60) {
                    len = inBuf.readUnsignedByte() + 1
                } else {
                    len = (inBuf.readShort() & 0xFFFF) + 1
                }
                inBuf.readBytes(output, op, len)
                op += len
            } else {
                // Copy
                int matchLen = ((tag >>> 2) & 0x3F) + MIN_MATCH
                int offset = inBuf.readShort() & 0xFFFF
                int matchSrc = op - offset
                if (matchSrc < 0) {
                    throw new IllegalStateException("Corrupt copy offset: ${offset} at op=${op}")
                }
                for (int i = 0; i < matchLen; i++) {
                    output[op++] = output[matchSrc + i]
                }
            }
        }

        output
    }

    private static int hash4(byte[] data, int idx) {
        int v = (data[idx] & 0xFF) |
                ((data[idx + 1] & 0xFF) << 8) |
                ((data[idx + 2] & 0xFF) << 16) |
                ((data[idx + 3] & 0xFF) << 24)
        (int) (((v * 0x1e35a7bdL) >>> 20) & (HASH_TABLE_SIZE - 1))
    }

    private static boolean match4(byte[] data, int a, int b) {
        data[a] == data[b] &&
                data[a + 1] == data[b + 1] &&
                data[a + 2] == data[b + 2] &&
                data[a + 3] == data[b + 3]
    }

    private static void emitLiteral(ByteBuf out, byte[] src, int start, int length) {
        if (length <= 0) return
        if (length <= 60) {
            out.writeByte(((length - 1) << 2) | 0)
        } else if (length <= 256) {
            out.writeByte((60 << 2) | 0)
            out.writeByte(length - 1)
        } else {
            out.writeByte((61 << 2) | 0)
            out.writeShort(length - 1)
        }
        out.writeBytes(src, start, length)
    }

    private static void emitCopy(ByteBuf out, int offset, int length) {
        out.writeByte((((length - MIN_MATCH) & 0x3F) << 2) | 1)
        out.writeShort(offset)
    }

    private SnappyCodec() {}
}
