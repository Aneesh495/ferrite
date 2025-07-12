package org.ferrite.bitstream

import groovy.transform.CompileStatic
import org.ferrite.common.ByteBuf

import java.io.InputStream
import java.io.OutputStream

/**
 * Variable-length integer encoding (LEB128/VarInt) with ZigZag signed representation.
 * Minimizes wire overhead for identifiers, lengths, and delta values.
 */
@CompileStatic
class VarInt {

    static int encodeZigZag32(int n) {
        (n << 1) ^ (n >> 31)
    }

    static int decodeZigZag32(int n) {
        (n >>> 1) ^ -(n & 1)
    }

    static long encodeZigZag64(long n) {
        (n << 1) ^ (n >> 63)
    }

    static long decodeZigZag64(long n) {
        (n >>> 1) ^ -(n & 1L)
    }

    static void writeVarInt(ByteBuf buf, int value) {
        int v = value
        while ((v & ~0x7F) != 0) {
            buf.writeByte((v & 0x7F) | 0x80)
            v >>>= 7
        }
        buf.writeByte(v & 0x7F)
    }

    static int readVarInt(ByteBuf buf) {
        int result = 0
        int shift = 0
        while (shift < 35) {
            byte b = buf.readByte()
            result |= (b & 0x7F) << shift
            if ((b & 0x80) == 0) {
                return result
            }
            shift += 7
        }
        throw new IllegalStateException("Malformed VarInt: exceeds 35-bit shift boundary")
    }

    static void writeVarLong(ByteBuf buf, long value) {
        long v = value
        while ((v & ~0x7FL) != 0L) {
            buf.writeByte((int) ((v & 0x7FL) | 0x80L))
            v >>>= 7
        }
        buf.writeByte((int) (v & 0x7FL))
    }

    static long readVarLong(ByteBuf buf) {
        long result = 0L
        int shift = 0
        while (shift < 70) {
            byte b = buf.readByte()
            result |= ((long) (b & 0x7F)) << shift
            if ((b & 0x80) == 0) {
                return result
            }
            shift += 7
        }
        throw new IllegalStateException("Malformed VarLong: exceeds 70-bit shift boundary")
    }

    static void writeSignedVarInt(ByteBuf buf, int value) {
        writeVarInt(buf, encodeZigZag32(value))
    }

    static int readSignedVarInt(ByteBuf buf) {
        decodeZigZag32(readVarInt(buf))
    }

    static void writeSignedVarLong(ByteBuf buf, long value) {
        writeVarLong(buf, encodeZigZag64(value))
    }

    static long readSignedVarLong(ByteBuf buf) {
        decodeZigZag64(readVarLong(buf))
    }

    private VarInt() {}
}
