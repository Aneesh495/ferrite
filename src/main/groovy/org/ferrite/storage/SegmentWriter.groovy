package org.ferrite.storage

import groovy.transform.CompileStatic
import org.ferrite.bitstream.VarInt
import org.ferrite.codec.CodecRegistry
import org.ferrite.common.ByteBuf
import org.ferrite.common.CRC32C
import org.ferrite.common.MetricPoint
import org.ferrite.common.TimeSeriesKey
import org.ferrite.index.BloomFilter

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Columnar segment file serializer. Compresses time series streams using Gorilla codecs,
 * constructs zero-copy block indexes, and builds Bloom filters for sub-millisecond point lookups.
 */
@CompileStatic
class SegmentWriter {
    static final int TRAILER_MAGIC = 0x54534D46 // "TSMF"

    static Path writeSegment(Path targetFile, Map<TimeSeriesKey, List<MetricPoint>> data) {
        if (targetFile == null || data == null || data.isEmpty()) {
            throw new IllegalArgumentException("Target file and data must be non-null and non-empty")
        }

        Files.createDirectories(targetFile.parent)

        // Compute overall min/max timestamps and total blocks
        long globalMinTs = Long.MAX_VALUE
        long globalMaxTs = Long.MIN_VALUE
        int blockCount = data.size()

        for (List<MetricPoint> pts : data.values()) {
            for (MetricPoint pt : pts) {
                if (pt.timestamp < globalMinTs) globalMinTs = pt.timestamp
                if (pt.timestamp > globalMaxTs) globalMaxTs = pt.timestamp
            }
        }
        if (globalMinTs == Long.MAX_VALUE) {
            globalMinTs = 0L
            globalMaxTs = 0L
        }

        BloomFilter bloom = new BloomFilter(Math.max(16, blockCount), 0.01)
        List<BlockMetadata> metadataList = new ArrayList<>(blockCount)

        FileChannel channel = FileChannel.open(
                targetFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        )

        channel.withCloseable {
            // Write Header placeholder
            SegmentHeader header = new SegmentHeader(1, 0, blockCount, globalMinTs, globalMaxTs)
            ByteBuf headerBuf = ByteBuf.allocate(SegmentHeader.HEADER_SIZE)
            header.writeTo(headerBuf)
            writeBufferToChannel(channel, headerBuf)

            long currentOffset = SegmentHeader.HEADER_SIZE

            // Write each series block
            for (Map.Entry<TimeSeriesKey, List<MetricPoint>> entry : data.entrySet()) {
                TimeSeriesKey key = entry.key
                List<MetricPoint> points = new ArrayList<>(entry.value)
                Collections.sort(points)

                int count = points.size()
                if (count == 0) continue

                long minTs = points.get(0).timestamp
                long maxTs = points.get(count - 1).timestamp
                double minVal = Double.MAX_VALUE
                double maxVal = -Double.MAX_VALUE

                long[] tsArray = new long[count]
                double[] valArray = new double[count]

                for (int i = 0; i < count; i++) {
                    MetricPoint pt = points.get(i)
                    tsArray[i] = pt.timestamp
                    valArray[i] = pt.value
                    if (pt.value < minVal) minVal = pt.value
                    if (pt.value > maxVal) maxVal = pt.value
                }

                byte[] compressedTs = CodecRegistry.compressTimestamps(tsArray)
                byte[] compressedVal = CodecRegistry.compressValues(valArray)

                int blockLen = 8 + compressedTs.length + compressedVal.length
                ByteBuf blockBuf = ByteBuf.allocate(16 + blockLen)

                int payloadCrc = CRC32C.compute(compressedTs) ^ CRC32C.compute(compressedVal)

                blockBuf.writeInt(blockLen)
                blockBuf.writeInt(payloadCrc)
                blockBuf.writeInt(compressedTs.length)
                blockBuf.writeInt(compressedVal.length)
                blockBuf.writeBytes(compressedTs)
                blockBuf.writeBytes(compressedVal)

                int totalBlockBytes = blockBuf.readableBytes()
                writeBufferToChannel(channel, blockBuf)

                metadataList.add(new BlockMetadata(
                        key, minTs, maxTs, minVal, maxVal, count, currentOffset, totalBlockBytes
                ))
                bloom.add(key.toCanonicalString())

                currentOffset += totalBlockBytes
            }

            // Write Table of Contents (TOC)
            long tocOffset = currentOffset
            ByteBuf tocBuf = ByteBuf.allocate(1024 + metadataList.size() * 128)
            VarInt.writeVarInt(tocBuf, metadataList.size())
            Collections.sort(metadataList)
            for (BlockMetadata meta : metadataList) {
                meta.writeTo(tocBuf)
            }

            // Write Bloom Filter
            bloom.writeTo(tocBuf)

            byte[] tocBytes = tocBuf.toByteArray()
            int tocCrc = CRC32C.compute(tocBytes)
            writeBufferToChannel(channel, tocBuf)

            // Write Footer (16 bytes)
            ByteBuf footerBuf = ByteBuf.allocate(16)
            footerBuf.writeLong(tocOffset)
            footerBuf.writeInt(tocCrc)
            footerBuf.writeInt(TRAILER_MAGIC)
            writeBufferToChannel(channel, footerBuf)

            channel.force(true)
        }

        targetFile
    }

    private static void writeBufferToChannel(FileChannel channel, ByteBuf buf) {
        ByteBuffer nio = buf.toNioByteBuffer()
        while (nio.hasRemaining()) {
            channel.write(nio)
        }
    }

    private SegmentWriter() {}
}
