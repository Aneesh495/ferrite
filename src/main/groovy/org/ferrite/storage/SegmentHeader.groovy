package org.ferrite.storage

import groovy.transform.CompileStatic
import org.ferrite.common.ByteBuf
import org.ferrite.common.Constants

/**
 * Header layout and validation for immutable Ferrite columnar segment files.
 */
@CompileStatic
class SegmentHeader {
    static final int HEADER_SIZE = 32

    final int version
    final int flags
    final int blockCount
    final long minTimestamp
    final long maxTimestamp

    SegmentHeader(int version, int flags, int blockCount, long minTimestamp, long maxTimestamp) {
        this.version = version
        this.flags = flags
        this.blockCount = blockCount
        this.minTimestamp = minTimestamp
        this.maxTimestamp = maxTimestamp
    }

    void writeTo(ByteBuf buf) {
        buf.writeBytes(Constants.MAGIC_BYTES)
        buf.writeShort(version)
        buf.writeShort(flags)
        buf.writeInt(blockCount)
        buf.writeLong(minTimestamp)
        buf.writeLong(maxTimestamp)
    }

    static SegmentHeader readFrom(ByteBuf buf) {
        if (buf.readableBytes() < HEADER_SIZE) {
            throw new IllegalArgumentException("Buffer underflow reading SegmentHeader")
        }

        byte[] magic = buf.readBytes(8)
        for (int i = 0; i < 8; i++) {
            if (magic[i] != Constants.MAGIC_BYTES[i]) {
                throw new IllegalStateException("Corrupt segment magic bytes")
            }
        }

        int version = buf.readShort() & 0xFFFF
        int flags = buf.readShort() & 0xFFFF
        int blockCount = buf.readInt()
        long minTs = buf.readLong()
        long maxTs = buf.readLong()

        new SegmentHeader(version, flags, blockCount, minTs, maxTs)
    }
}
