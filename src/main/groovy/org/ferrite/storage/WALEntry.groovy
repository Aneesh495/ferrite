package org.ferrite.storage

import groovy.transform.CompileStatic
import org.ferrite.bitstream.VarInt
import org.ferrite.common.ByteBuf
import org.ferrite.common.CRC32C
import org.ferrite.common.MetricPoint
import org.ferrite.common.TimeSeriesKey

/**
 * Framed Write-Ahead Log entry with CRC32C validation.
 * Ensures crash consistency, data durability, and immediate recovery on ungraceful shutdown.
 */
@CompileStatic
class WALEntry {
    static final int WAL_MAGIC = 0x57414C31 // "WAL1"
    static final int HEADER_SIZE = 20 // 4 (magic) + 4 (len) + 4 (crc) + 8 (seq)

    final long sequenceNumber
    final WALEntryType type
    final TimeSeriesKey seriesKey
    final List<MetricPoint> points

    WALEntry(long sequenceNumber, WALEntryType type, TimeSeriesKey seriesKey, List<MetricPoint> points) {
        this.sequenceNumber = sequenceNumber
        this.type = type != null ? type : WALEntryType.APPEND_POINTS
        this.seriesKey = seriesKey
        List<MetricPoint> copy = new ArrayList<MetricPoint>()
        if (points != null) {
            copy.addAll(points)
        }
        this.points = Collections.unmodifiableList(copy)
    }

    static WALEntry append(long seq, TimeSeriesKey key, List<MetricPoint> points) {
        new WALEntry(seq, WALEntryType.APPEND_POINTS, key, points)
    }

    static WALEntry delete(long seq, TimeSeriesKey key) {
        new WALEntry(seq, WALEntryType.DELETE_SERIES, key, Collections.<MetricPoint>emptyList())
    }

    static WALEntry checkpoint(long seq) {
        new WALEntry(seq, WALEntryType.CHECKPOINT, null, Collections.<MetricPoint>emptyList())
    }

    void writeTo(ByteBuf buf) {
        // Serialize payload first to calculate CRC and length
        ByteBuf payload = ByteBuf.allocate(64 + points.size() * 16)
        payload.writeByte(type.id)
        if (seriesKey != null) {
            payload.writeStringUtf8(seriesKey.toCanonicalString())
        } else {
            payload.writeStringUtf8("")
        }

        VarInt.writeVarInt(payload, points.size())
        for (int i = 0; i < points.size(); i++) {
            MetricPoint pt = points.get(i)
            payload.writeLong(pt.timestamp)
            payload.writeDouble(pt.value)
        }

        byte[] payloadBytes = payload.toByteArray()
        int crc = CRC32C.compute(payloadBytes)

        buf.writeInt(WAL_MAGIC)
        buf.writeInt(payloadBytes.length)
        buf.writeInt(crc)
        buf.writeLong(sequenceNumber)
        buf.writeBytes(payloadBytes)
    }

    static WALEntry readFrom(ByteBuf buf) {
        if (buf.readableBytes() < HEADER_SIZE) {
            return null
        }

        buf.markReaderIndex()
        int magic = buf.readInt()
        if (magic != WAL_MAGIC) {
            buf.resetReaderIndex()
            throw new CorruptWALException("Invalid WAL magic: expected ${WAL_MAGIC}, got ${magic}")
        }

        int payloadLen = buf.readInt()
        int expectedCrc = buf.readInt()
        long seq = buf.readLong()

        if (buf.readableBytes() < payloadLen) {
            // Incomplete write (torn frame)
            buf.resetReaderIndex()
            return null
        }

        byte[] payloadBytes = buf.readBytes(payloadLen)
        int actualCrc = CRC32C.compute(payloadBytes)
        if (actualCrc != expectedCrc) {
            throw new CorruptWALException("CRC mismatch in WAL entry: expected ${expectedCrc}, got ${actualCrc}")
        }

        ByteBuf pBuf = ByteBuf.wrap(payloadBytes)
        byte typeId = pBuf.readByte()
        WALEntryType type = WALEntryType.fromId(typeId)

        String keyStr = pBuf.readStringUtf8()
        TimeSeriesKey key = (keyStr != null && !keyStr.isEmpty()) ? TimeSeriesKey.parse(keyStr) : null

        int pointCount = VarInt.readVarInt(pBuf)
        List<MetricPoint> pts = new ArrayList<MetricPoint>(pointCount)
        for (int i = 0; i < pointCount; i++) {
            long ts = pBuf.readLong()
            double val = pBuf.readDouble()
            pts.add(MetricPoint.of(ts, val))
        }

        new WALEntry(seq, type, key, pts)
    }
}
