package org.ferrite.protocol

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode

/**
 * Low-overhead multiplexed binary frame descriptor for the Ferrite Framed Protocol (FFP).
 * Wire Layout (20-byte fixed header + variable payload + 4-byte CRC32C):
 * [0..1]   Magic (0xFE54)
 * [2]      Version (0x01)
 * [3]      Type (OpCode)
 * [4..5]   Flags
 * [6..13]  Stream / Correlation ID (64-bit int)
 * [14..17] Payload Length (32-bit int)
 * [18..18+len-1] Payload Bytes
 * [18+len..18+len+3] CRC32C Checksum
 */
@CompileStatic
@EqualsAndHashCode
class BinaryFrame {

    public static final short MAGIC = (short) 0xFE54
    public static final byte VERSION = (byte) 1

    enum Type {
        PING((byte) 0x01),
        PONG((byte) 0x02),
        INGEST_BATCH((byte) 0x10),
        INGEST_ACK((byte) 0x11),
        QUERY_REQ((byte) 0x20),
        QUERY_RESP((byte) 0x21),
        ERROR((byte) 0xFF)

        final byte code

        Type(byte code) {
            this.code = code
        }

        static Type fromCode(byte code) {
            for (Type t : values()) {
                if (t.code == code) return t
            }
            throw new IllegalArgumentException("Unknown frame opcode: ${code}")
        }
    }

    final byte version
    final Type type
    final short flags
    final long streamId
    final byte[] payload

    BinaryFrame(Type type, long streamId, byte[] payload = new byte[0], short flags = (short) 0) {
        this(VERSION, type, flags, streamId, payload != null ? payload : new byte[0])
    }

    BinaryFrame(byte version, Type type, short flags, long streamId, byte[] payload) {
        this.version = version
        this.type = type
        this.flags = flags
        this.streamId = streamId
        this.payload = payload != null ? payload : new byte[0]
    }

    int getPayloadLength() {
        payload.length
    }

    static BinaryFrame ping(long streamId) {
        new BinaryFrame(Type.PING, streamId)
    }

    static BinaryFrame pong(long streamId) {
        new BinaryFrame(Type.PONG, streamId)
    }

    static BinaryFrame error(long streamId, String errorMessage) {
        byte[] b = errorMessage != null ? errorMessage.getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[0]
        new BinaryFrame(Type.ERROR, streamId, b)
    }
}
