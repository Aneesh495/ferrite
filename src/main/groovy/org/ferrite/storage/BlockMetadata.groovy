package org.ferrite.storage

import groovy.transform.CompileStatic
import org.ferrite.bitstream.VarInt
import org.ferrite.common.ByteBuf
import org.ferrite.common.TimeSeriesKey

/**
 * Metadata record for an individual compressed time series block within a segment file.
 * Enables zero-scan min/max predicate pushdown and binary search offset indexing.
 */
@CompileStatic
class BlockMetadata implements Comparable<BlockMetadata>, Serializable {
    private static final long serialVersionUID = 1L

    final TimeSeriesKey seriesKey
    final long minTimestamp
    final long maxTimestamp
    final double minValue
    final double maxValue
    final int pointCount
    final long blockOffset
    final int blockLength

    BlockMetadata(TimeSeriesKey seriesKey, long minTimestamp, long maxTimestamp,
                  double minValue, double maxValue, int pointCount, long blockOffset, int blockLength) {
        this.seriesKey = seriesKey
        this.minTimestamp = minTimestamp
        this.maxTimestamp = maxTimestamp
        this.minValue = minValue
        this.maxValue = maxValue
        this.pointCount = pointCount
        this.blockOffset = blockOffset
        this.blockLength = blockLength
    }

    void writeTo(ByteBuf buf) {
        buf.writeStringUtf8(seriesKey.toCanonicalString())
        buf.writeLong(minTimestamp)
        buf.writeLong(maxTimestamp)
        buf.writeDouble(minValue)
        buf.writeDouble(maxValue)
        VarInt.writeVarInt(buf, pointCount)
        buf.writeLong(blockOffset)
        buf.writeInt(blockLength)
    }

    static BlockMetadata readFrom(ByteBuf buf) {
        String keyStr = buf.readStringUtf8()
        TimeSeriesKey key = TimeSeriesKey.parse(keyStr)
        long minTs = buf.readLong()
        long maxTs = buf.readLong()
        double minVal = buf.readDouble()
        double maxVal = buf.readDouble()
        int count = VarInt.readVarInt(buf)
        long offset = buf.readLong()
        int len = buf.readInt()

        new BlockMetadata(key, minTs, maxTs, minVal, maxVal, count, offset, len)
    }

    @Override
    int compareTo(BlockMetadata o) {
        int cmp = this.seriesKey.compareTo(o.seriesKey)
        if (cmp != 0) return cmp
        Long.compare(this.minTimestamp, o.minTimestamp)
    }
}
