package org.ferrite.chaos

import org.ferrite.common.MetricPoint
import org.ferrite.common.TimeRange
import org.ferrite.common.TimeSeriesKey
import org.ferrite.query.QueryEngine
import org.ferrite.query.QueryResult
import org.ferrite.storage.StorageEngine
import org.ferrite.storage.WALEntry
import org.ferrite.storage.WALRecoveryReader
import org.ferrite.storage.WriteAheadLog
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class ChaosSimulationSpec extends Specification {

    def "Chaos: should recover all committed points after abrupt power-loss crash"() {
        given: "a temporary database directory"
        Path dataDir = Files.createTempDirectory("ferrite-chaos-crash-")
        TimeSeriesKey key = TimeSeriesKey.parse("disk.io.ops{device=nvme0n1}")
        int pointCount = 5000

        when: "engine ingests points with async commits"
        StorageEngine engine = new StorageEngine(dataDir)
        for (int i = 0; i < pointCount; i++) {
            engine.write(key, MetricPoint.of(1000L + i, (double) i))
        }

        and: "simulating sudden unannounced process crash without clean shutdown"
        engine.triggerFlush()
        // Do not call engine.close()

        and: "restarting fresh storage engine on the same directory"
        StorageEngine recoveredEngine = new StorageEngine(dataDir)
        QueryEngine qe = new QueryEngine(recoveredEngine)
        QueryResult res = qe.executeRaw(key, TimeRange.allTime())

        then: "all 5000 points are fully recovered from WAL and segments"
        res.totalPoints() == pointCount

        cleanup:
        if (recoveredEngine != null) recoveredEngine.close()
        if (dataDir != null) dataDir.toFile().deleteDir()
    }

    def "Chaos: should detect bit-rot and torn writes in WAL log files"() {
        given:
        Path walDir = Files.createTempDirectory("chaos-wal-")
        WriteAheadLog wal = new WriteAheadLog(walDir, 1024 * 1024, true)
        TimeSeriesKey key = TimeSeriesKey.parse("cpu.temp{core=0}")

        when: "writing 100 valid entries"
        100.times { i ->
            wal.append(key, [MetricPoint.of(1000L + i, 45.0d + i)])
        }
        wal.flush()
        wal.close()

        and: "injecting bit-rot / corrupting the 50th record on disk"
        Path walFile = Files.list(walDir).findFirst().get()
        byte[] walBytes = Files.readAllBytes(walFile)
        int corruptOffset = Math.min(1500, walBytes.length - 10)
        walBytes[corruptOffset] = (byte) (walBytes[corruptOffset] ^ 0xFF)
        Files.write(walFile, walBytes, StandardOpenOption.TRUNCATE_EXISTING)

        and: "reading the corrupted WAL file"
        List<WALEntry> recovered = []
        def result = WALRecoveryReader.recover(walDir) { recovered.add(it) }

        then: "reader truncates at the corrupted boundary and preserves valid prefix"
        recovered.size() < 100
        recovered.size() > 0

        cleanup:
        if (walDir != null) walDir.toFile().deleteDir()
    }

    def "Chaos: should withstand high-concurrency multi-threaded writes and compaction"() {
        given:
        Path dataDir = Files.createTempDirectory("ferrite-chaos-concurrent-")
        StorageEngine engine = new StorageEngine(dataDir)
        int threadCount = 4
        int writesPerThread = 2500
        def pool = Executors.newFixedThreadPool(threadCount)

        when: "multiple threads concurrently write, flush, and compact"
        List<Callable<Void>> tasks = []
        threadCount.times { threadIdx ->
            tasks.add({
                TimeSeriesKey key = TimeSeriesKey.parse("worker.throughput{thread=${threadIdx}}")
                for (int i = 0; i < writesPerThread; i++) {
                    engine.write(key, MetricPoint.of(1000L + i, (double) (i * 2)))
                    if (i % 800 == 0) {
                        engine.triggerFlush()
                    }
                }
                null
            } as Callable<Void>)
        }

        def futures = pool.invokeAll(tasks)
        futures.each { it.get() }
        pool.shutdown()

        and: "flushing final points"
        engine.triggerFlush()

        then: "total points written equals expected total"
        engine.stats.totalPointsWritten == (long) (threadCount * writesPerThread)

        cleanup:
        if (engine != null) engine.close()
        if (dataDir != null) dataDir.toFile().deleteDir()
    }
}
