package org.ferrite.protocol

import groovy.transform.CompileStatic
import org.ferrite.common.ByteBuf
import org.ferrite.common.CRC32C

/**
 * High-throughput binary frame encoder and decoder for network stream processing.
 * Verifies frame integrity with CRC32C checksums and handles stream fragmentation.
 */
@CompileStatic
class BinaryFrameCodec {

    public static final int HEADER_SIZE = 18 // 2(magic) + 1(ver) + 1(type) + 2(flags) + 8(streamId) + 4(len)
    public static final int TRAILER_SIZE = 4 // 4(crc)
    public static final int MIN_FRAME_SIZE = HEADER_SIZE + TRAILER_SIZE

    /**
     * Encodes a BinaryFrame into a framed byte array with header and CRC32C checksum.
     */
    static byte[] encode(BinaryFrame frame) {
        int payloadLen = frame.payload.length
        int totalLen = HEADER_SIZE + payloadLen + TRAILER_SIZE
        ByteBuf buf = ByteBuf.allocate(totalLen)

        buf.writeShort(BinaryFrame.MAGIC)
        buf.writeByte(frame.version)
        buf.writeByte(frame.type.code)
        buf.writeShort(frame.flags)
        buf.writeLong(frame.streamId)
        buf.writeInt(payloadLen)

        if (payloadLen > 0) {
            buf.writeBytes(frame.payload)
        }

        // Calculate CRC32C over header and payload
        byte[] raw = buf.toByteArray()
        int checksum = CRC32C.compute(raw, 0, HEADER_SIZE + payloadLen)
        buf.writeInt(checksum)

        buf.toByteArray()
    }

    /**
     * Attempts to decode a complete BinaryFrame from the input buffer.
     * Returns null if incomplete frame data has arrived (fragmentation).
     *
     * @throws IllegalArgumentException on magic mismatch or CRC corruption.
     */
    static BinaryFrame decode(ByteBuf buf) {
        if (buf == null || buf.readableBytes() < MIN_FRAME_SIZE) {
            return null
        }

        buf.markReaderIndex()

        short magic = buf.readShort()
        if (magic != BinaryFrame.MAGIC) {
            throw new IllegalArgumentException("Corrupt frame magic: expected 0x${Integer.toHexString(BinaryFrame.MAGIC & 0xFFFF)}, got: 0x${Integer.toHexString(magic & 0xFFFF)}")
        }

        byte version = buf.readByte()
        byte typeCode = buf.readByte()
        short flags = buf.readShort()
        long streamId = buf.readLong()
        int payloadLen = buf.readInt()

        if (payloadLen < 0 || payloadLen > 64 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid payload length: ${payloadLen}")
        }

        if (buf.readableBytes() < payloadLen + TRAILER_SIZE) {
            // Incomplete frame: rewind reader index and await more bytes
            buf.resetReaderIndex()
            return null
        }

        byte[] payload = new byte[payloadLen]
        if (payloadLen > 0) {
            buf.readBytes(payload, 0, payloadLen)
        }

        int expectedCrc = buf.readInt()

        // Verify CRC32C
        int frameTotalBytes = HEADER_SIZE + payloadLen
        buf.resetReaderIndex()
        byte[] frameBytes = new byte[frameTotalBytes]
        buf.readBytes(frameBytes, 0, frameTotalBytes)
        // Skip reading CRC again
        buf.readInt()

        int computedCrc = CRC32C.compute(frameBytes, 0, frameTotalBytes)
        if (computedCrc != expectedCrc) {
            throw new IllegalStateException("CRC32C verification failed: computed ${computedCrc}, expected ${expectedCrc}")
        }

        BinaryFrame.Type type = BinaryFrame.Type.fromCode(typeCode)
        new BinaryFrame(version, type, flags, streamId, payload)
    }
}
