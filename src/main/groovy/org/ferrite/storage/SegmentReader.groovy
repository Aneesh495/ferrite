package org.ferrite.storage

import groovy.transform.CompileStatic
import org.ferrite.bitstream.VarInt
import org.ferrite.codec.CodecRegistry
import org.ferrite.common.ByteBuf
import org.ferrite.common.CRC32C
import org.ferrite.common.MetricPoint
import org.ferrite.common.TimeRange
import org.ferrite.common.TimeSeriesKey
import org.ferrite.index.BloomFilter

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Immutable columnar segment reader.
 * Evaluates Bloom filters and min/max block metadata for zero-I/O block skipping,
 * reading only the exact compressed column slices required by user queries.
 */
@CompileStatic
class SegmentReader implements AutoCloseable {
    private final Path segmentPath
    private final FileChannel channel
    private final SegmentHeader header
    private final BloomFilter bloomFilter
    private final Map<TimeSeriesKey, BlockMetadata> indexMap
    private final long fileSize

    SegmentReader(Path segmentPath) {
        if (!Files.exists(segmentPath)) {
            throw new FileNotFoundException("Segment file not found: ${segmentPath}")
        }
        this.segmentPath = segmentPath
        this.fileSize = Files.size(segmentPath)
        if (fileSize < SegmentHeader.HEADER_SIZE + 16) {
            throw new CorruptSegmentException("Segment file truncated: ${segmentPath} size=${fileSize}")
        }

        this.channel = FileChannel.open(segmentPath, StandardOpenOption.READ)

        // Read Header
        ByteBuf hBuf = readBytesFromChannel(channel, 0L, SegmentHeader.HEADER_SIZE)
        this.header = SegmentHeader.readFrom(hBuf)

        // Read Footer (last 16 bytes)
        ByteBuf fBuf = readBytesFromChannel(channel, fileSize - 16L, 16)
        long tocOffset = fBuf.readLong()
        int expectedTocCrc = fBuf.readInt()
        int trailerMagic = fBuf.readInt()

        if (trailerMagic != SegmentWriter.TRAILER_MAGIC) {
            channel.close()
            throw new CorruptSegmentException("Invalid trailer magic in segment: ${segmentPath}")
        }

        int tocLength = (int) (fileSize - 16L - tocOffset)
        ByteBuf tocBuf = readBytesFromChannel(channel, tocOffset, tocLength)
        byte[] tocBytes = tocBuf.toByteArray()
        int actualTocCrc = CRC32C.compute(tocBytes)
        if (actualTocCrc != expectedTocCrc) {
            channel.close()
            throw new CorruptSegmentException("TOC checksum mismatch in segment: ${segmentPath}")
        }

        // Deserialize TOC
        int blockCount = VarInt.readVarInt(tocBuf)
        Map<TimeSeriesKey, BlockMetadata> map = new HashMap<>(blockCount)
        for (int i = 0; i < blockCount; i++) {
            BlockMetadata meta = BlockMetadata.readFrom(tocBuf)
            map.put(meta.seriesKey, meta)
        }
        this.indexMap = Collections.unmodifiableMap(map)

        // Deserialize Bloom Filter
        this.bloomFilter = BloomFilter.readFrom(tocBuf)
    }

    boolean hasSeries(TimeSeriesKey key) {
        if (!bloomFilter.mightContain(key.toCanonicalString())) {
            return false
        }
        indexMap.containsKey(key)
    }

    List<MetricPoint> query(TimeSeriesKey key, TimeRange range) {
        if (!hasSeries(key)) {
            return Collections.emptyList()
        }

        BlockMetadata meta = indexMap.get(key)
        if (meta == null) return Collections.emptyList()

        // Time range pruning
        if (range.fromEpochMs > meta.maxTimestamp || range.toEpochMs < meta.minTimestamp) {
            return Collections.emptyList()
        }

        // Read compressed block from disk
        ByteBuf blockBuf = readBytesFromChannel(channel, meta.blockOffset, meta.blockLength)
        int blockLen = blockBuf.readInt()
        int payloadCrc = blockBuf.readInt()
        int tsLen = blockBuf.readInt()
        int valLen = blockBuf.readInt()

        byte[] compressedTs = blockBuf.readBytes(tsLen)
        byte[] compressedVal = blockBuf.readBytes(valLen)

        int actualCrc = CRC32C.compute(compressedTs) ^ CRC32C.compute(compressedVal)
        if (actualCrc != payloadCrc) {
            throw new CorruptSegmentException("Block checksum mismatch for series ${key} at offset ${meta.blockOffset}")
        }

        long[] timestamps = CodecRegistry.decompressTimestamps(compressedTs, meta.pointCount)
        double[] values = CodecRegistry.decompressValues(compressedVal, meta.pointCount)

        List<MetricPoint> results = new ArrayList<>(meta.pointCount)
        for (int i = 0; i < meta.pointCount; i++) {
            long ts = timestamps[i]
            if (range.contains(ts)) {
                results.add(MetricPoint.of(ts, values[i]))
            }
        }
        results
    }

    Set<TimeSeriesKey> getAllKeys() {
        indexMap.keySet()
    }

    long getMinTimestamp() {
        header.minTimestamp
    }

    long getMaxTimestamp() {
        header.maxTimestamp
    }

    int getBlockCount() {
        header.blockCount
    }

    Path getSegmentPath() {
        segmentPath
    }

    private static ByteBuf readBytesFromChannel(FileChannel ch, long position, int length) {
        ByteBuffer buf = ByteBuffer.allocate(length)
        long currentPos = position
        while (buf.hasRemaining()) {
            int read = ch.read(buf, currentPos)
            if (read < 0) break
            currentPos += read
        }
        buf.flip()
        ByteBuf.wrap(buf)
    }

    @Override
    void close() {
        if (channel != null && channel.isOpen()) {
            channel.close()
        }
    }
}
