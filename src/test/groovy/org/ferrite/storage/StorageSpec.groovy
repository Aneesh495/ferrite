package org.ferrite.storage

import org.ferrite.common.MetricPoint
import org.ferrite.common.TimeRange
import org.ferrite.common.TimeSeriesKey
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

class StorageSpec extends Specification {

    Path tempDir

    def setup() {
        tempDir = Files.createTempDirectory("ferrite-storage-test")
    }

    def cleanup() {
        if (tempDir && Files.exists(tempDir)) {
            tempDir.toFile().deleteDir()
        }
    }

    def "should write and read wal entries with crc verification"() {
        given:
        Path walDir = tempDir.resolve("wal")
        def wal = new WriteAheadLog(walDir, 1024 * 1024, true)
        def key = TimeSeriesKey.of("cpu.usage", [host: "srv-01"])
        def points = [
                MetricPoint.of(1000L, 10.5),
                MetricPoint.of(2000L, 20.5),
                MetricPoint.of(3000L, 30.5)
        ]

        when:
        long seq = wal.append(key, points)
        wal.flush()
        wal.close()

        then:
        seq == 1L

        when:
        List<WALEntry> recovered = []
        def result = WALRecoveryReader.recover(walDir) { recovered.add(it) }

        then:
        result.entriesRecovered == 1L
        result.highestSequenceNumber == 1L
        !result.tornWriteTruncated
        recovered.size() == 1
        recovered[0].seriesKey == key
        recovered[0].points.size() == 3
        recovered[0].points[0].value == 10.5
    }

    def "should truncate torn writes during wal recovery"() {
        given:
        Path walDir = tempDir.resolve("wal-torn")
        def wal = new WriteAheadLog(walDir, 1024 * 1024, true)
        def key = TimeSeriesKey.of("mem.free", [host: "srv-02"])
        wal.append(key, [MetricPoint.of(1000L, 100.0)])
        wal.flush()
        wal.close()

        // Append 7 bytes of garbage simulating a torn power failure write
        Path walFile = Files.list(walDir).findFirst().get()
        Files.write(walFile, [0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07] as byte[], java.nio.file.StandardOpenOption.APPEND)

        when:
        List<WALEntry> recovered = []
        def result = WALRecoveryReader.recover(walDir) { recovered.add(it) }

        then:
        result.entriesRecovered == 1L
        result.tornWriteTruncated
        recovered.size() == 1
    }

    def "should write and query immutable columnar segment file"() {
        given:
        Path segPath = tempDir.resolve("test.ferrite")
        def k1 = TimeSeriesKey.of("net.rx_bytes", [iface: "eth0"])
        def k2 = TimeSeriesKey.of("net.tx_bytes", [iface: "eth0"])

        def pts1 = [
                MetricPoint.of(1000L, 1024.0),
                MetricPoint.of(2000L, 2048.0),
                MetricPoint.of(3000L, 4096.0)
        ]
        def pts2 = [
                MetricPoint.of(1000L, 512.0),
                MetricPoint.of(2000L, 1024.0)
        ]

        when:
        SegmentWriter.writeSegment(segPath, [(k1): pts1, (k2): pts2])
        def reader = new SegmentReader(segPath)

        then:
        reader.blockCount == 2
        reader.hasSeries(k1)
        reader.hasSeries(k2)
        !reader.hasSeries(TimeSeriesKey.of("nonexistent", Collections.emptyMap()))

        when:
        def q1 = reader.query(k1, TimeRange.between(1500L, 2500L))
        def q2 = reader.query(k2, TimeRange.between(0L, 5000L))

        then:
        q1.size() == 1
        q1[0].timestamp == 2000L
        q1[0].value == 2048.0

        q2.size() == 2
        q2[0].value == 512.0
        q2[1].value == 1024.0

        cleanup:
        reader.close()
    }

    def "should compact segments with size-tiered compactor"() {
        given:
        Path dataDir = tempDir.resolve("compaction-data")
        Files.createDirectories(dataDir)

        def key = TimeSeriesKey.of("disk.io", [dev: "sda"])
        List<SegmentReader> readers = []

        for (int i = 0; i < 4; i++) {
            Path segPath = dataDir.resolve("seg-${i}.ferrite")
            def pts = [MetricPoint.of((long) (i * 1000), (double) (i * 10))]
            SegmentWriter.writeSegment(segPath, [(key): pts])
            readers.add(new SegmentReader(segPath))
        }

        when:
        Path compacted = SizeTieredCompactor.compactSegments(dataDir, readers, 4)
        def compReader = new SegmentReader(compacted)
        def allPoints = compReader.query(key, TimeRange.allTime())

        then:
        compacted != null
        Files.exists(compacted)
        allPoints.size() == 4
        allPoints[0].timestamp == 0L
        allPoints[3].timestamp == 3000L

        cleanup:
        readers.each { it.close() }
        compReader.close()
    }

    def "should downsample raw points into statistical rollups"() {
        given:
        // 60 samples representing 1 second intervals across 1 minute
        List<MetricPoint> raw = []
        for (int i = 0; i < 60; i++) {
            raw.add(MetricPoint.of((long) (i * 1000), (double) (i + 1)))
        }

        when:
        def meanRollup = Downsampler.downsample(raw, 60000L, Downsampler.RollupAgg.MEAN)
        def maxRollup = Downsampler.downsample(raw, 60000L, Downsampler.RollupAgg.MAX)
        def minRollup = Downsampler.downsample(raw, 60000L, Downsampler.RollupAgg.MIN)
        def countRollup = Downsampler.downsample(raw, 60000L, Downsampler.RollupAgg.COUNT)

        then:
        meanRollup.size() == 1
        meanRollup[0].timestamp == 0L
        meanRollup[0].value == 30.5 // Average of 1..60 = (1 + 60) / 2 = 30.5

        maxRollup[0].value == 60.0
        minRollup[0].value == 1.0
        countRollup[0].value == 60.0
    }

    def "should handle end to end writes, flushes and crash recovery in storage engine"() {
        given:
        Path engineDir = tempDir.resolve("engine")
        def engine = new StorageEngine(engineDir, 2048, true) // Small threshold to trigger flush
        def key = TimeSeriesKey.of("http_requests", [code: "200"])

        when:
        for (int i = 0; i < 50; i++) {
            engine.write(key, MetricPoint.of((long) (i * 100), (double) (i * 2)))
        }

        then:
        engine.stats.totalPointsWritten == 50L

        when:
        def memQuery = engine.query(key, TimeRange.between(0L, 500L))

        then:
        memQuery.size() == 6 // 0, 100, 200, 300, 400, 500

        when:
        // Force flush and restart
        engine.triggerFlush()
        engine.close()

        def restarted = new StorageEngine(engineDir, 2048, true)
        def diskQuery = restarted.query(key, TimeRange.allTime())

        then:
        diskQuery.size() == 50
        diskQuery[0].value == 0.0
        diskQuery[49].value == 98.0

        cleanup:
        restarted.close()
    }
}
