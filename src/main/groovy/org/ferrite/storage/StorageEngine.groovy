package org.ferrite.storage

import groovy.transform.CompileStatic
import org.ferrite.common.Constants
import org.ferrite.common.MetricPoint
import org.ferrite.common.TimeRange
import org.ferrite.common.TimeSeriesKey
import org.ferrite.index.InvertedIndex

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Primary storage coordinator managing LSM-tree lifecycle:
 * write-ahead logging, active MemTables, segment flushing, and multi-tier compaction.
 */
@CompileStatic
class StorageEngine implements AutoCloseable {
    private final Path baseDir
    private final Path dataDir
    private final Path walDir
    private final long memtableThresholdBytes
    private final InvertedIndex invertedIndex
    private final WriteAheadLog wal

    private volatile MemTable activeMemTable
    private volatile MemTable flushingMemTable
    private final List<SegmentReader> segmentReaders = new CopyOnWriteArrayList<>()
    private final ReentrantReadWriteLock flushLock = new ReentrantReadWriteLock()

    private final AtomicLong totalPointsWritten = new AtomicLong(0L)
    private final AtomicLong flushCounter = new AtomicLong(0L)
    private final AtomicLong compactionCounter = new AtomicLong(0L)
    private volatile boolean closed = false

    StorageEngine(Path baseDir) {
        this(baseDir, Constants.DEFAULT_MEMTABLE_THRESHOLD_BYTES, false)
    }

    StorageEngine(Path baseDir, long memtableThresholdBytes, boolean syncWal) {
        this.baseDir = baseDir
        this.dataDir = baseDir.resolve("data")
        this.walDir = baseDir.resolve("wal")
        this.memtableThresholdBytes = memtableThresholdBytes
        this.invertedIndex = new InvertedIndex()

        Files.createDirectories(dataDir)
        Files.createDirectories(walDir)

        // Load existing segment files
        loadExistingSegments()

        // Recover from WAL
        this.activeMemTable = new MemTable()
        this.wal = new WriteAheadLog(walDir, Constants.DEFAULT_WAL_SEGMENT_MAX_BYTES, syncWal)
        recoverWal()
    }

    void write(TimeSeriesKey key, MetricPoint point) {
        write(key, Collections.singletonList(point))
    }

    void write(TimeSeriesKey key, List<MetricPoint> points) {
        if (closed) throw new IllegalStateException("StorageEngine is closed")
        if (key == null || points == null || points.isEmpty()) return

        invertedIndex.indexSeries(key)

        long seq = wal.append(key, points)
        activeMemTable.putAll(key, points, seq)
        totalPointsWritten.addAndGet(points.size())

        if (activeMemTable.approximateSizeBytes >= memtableThresholdBytes) {
            triggerFlush()
        }
    }

    List<MetricPoint> query(TimeSeriesKey key, TimeRange range) {
        if (key == null || range == null) return Collections.emptyList()

        Map<Long, Double> dedupMap = new TreeMap<>()

        // 1. Query segments
        for (SegmentReader reader : segmentReaders) {
            List<MetricPoint> segPoints = reader.query(key, range)
            for (MetricPoint pt : segPoints) {
                dedupMap.put(pt.timestamp, pt.value)
            }
        }

        // 2. Query flushing memtable
        MemTable flushing = this.flushingMemTable
        if (flushing != null) {
            List<MetricPoint> flushPoints = flushing.query(key, range)
            for (MetricPoint pt : flushPoints) {
                dedupMap.put(pt.timestamp, pt.value)
            }
        }

        // 3. Query active memtable
        List<MetricPoint> activePoints = activeMemTable.query(key, range)
        for (MetricPoint pt : activePoints) {
            dedupMap.put(pt.timestamp, pt.value)
        }

        if (dedupMap.isEmpty()) {
            return Collections.emptyList()
        }

        List<MetricPoint> results = new ArrayList<>(dedupMap.size())
        for (Map.Entry<Long, Double> entry : dedupMap.entrySet()) {
            results.add(MetricPoint.of(entry.key, entry.value))
        }
        results
    }

    List<TimeSeriesKey> searchSeries(String metricName, Map<String, String> tags) {
        invertedIndex.search(metricName, tags)
    }

    synchronized void triggerFlush() {
        if (closed) return
        if (activeMemTable.pointCount == 0L) return

        flushLock.writeLock().lock()
        MemTable toFlush
        try {
            activeMemTable.makeReadOnly()
            toFlush = activeMemTable
            this.flushingMemTable = toFlush
            this.activeMemTable = new MemTable()
        } finally {
            flushLock.writeLock().unlock()
        }

        try {
            Map<TimeSeriesKey, List<MetricPoint>> data = toFlush.dumpAll()
            if (!data.isEmpty()) {
                long gen = flushCounter.incrementAndGet()
                String segName = String.format("seg-%08d.ferrite", gen)
                Path segPath = dataDir.resolve(segName)
                SegmentWriter.writeSegment(segPath, data)

                SegmentReader reader = new SegmentReader(segPath)
                segmentReaders.add(reader)
            }
        } finally {
            this.flushingMemTable = null
        }
    }

    synchronized void triggerCompaction(int mergeThreshold) {
        if (closed || segmentReaders.size() < mergeThreshold) return
        List<SegmentReader> candidateList = new ArrayList<>(segmentReaders)
        Path compacted = SizeTieredCompactor.compactSegments(dataDir, candidateList, mergeThreshold)
        if (compacted != null && Files.exists(compacted)) {
            SegmentReader newReader = new SegmentReader(compacted)
            segmentReaders.add(newReader)

            // Remove compacted old readers and delete files
            for (SegmentReader oldReader : candidateList) {
                segmentReaders.remove(oldReader)
                oldReader.close()
                try {
                    Files.deleteIfExists(oldReader.segmentPath)
                } catch (Exception ignored) {}
            }
            compactionCounter.incrementAndGet()
        }
    }

    StorageStats getStats() {
        long totalSegBytes = 0L
        for (SegmentReader r : segmentReaders) {
            try {
                totalSegBytes += Files.size(r.segmentPath)
            } catch (Exception ignored) {}
        }
        new StorageStats(
                totalPointsWritten.get(),
                activeMemTable.pointCount,
                activeMemTable.approximateSizeBytes,
                segmentReaders.size(),
                totalSegBytes,
                flushCounter.get(),
                compactionCounter.get()
        )
    }

    InvertedIndex getInvertedIndex() {
        invertedIndex
    }

    Path getDataDir() {
        dataDir
    }

    private void loadExistingSegments() {
        if (!Files.exists(dataDir)) return
        List<Path> files = new ArrayList<>()
        Files.list(dataDir).withCloseable { stream ->
            stream.filter { p -> p.fileName.toString().endsWith(".ferrite") }
                    .sorted { a, b -> a.fileName.toString() <=> b.fileName.toString() }
                    .forEach { p -> files.add(p) }
        }

        for (Path p : files) {
            try {
                SegmentReader reader = new SegmentReader(p)
                segmentReaders.add(reader)
                for (TimeSeriesKey k : reader.allKeys) {
                    invertedIndex.indexSeries(k)
                }
            } catch (Exception e) {
                // Corrupt segment
                System.err.println("Skipping corrupt segment file: " + p + ": " + e.message)
            }
        }
    }

    private void recoverWal() {
        WALRecoveryReader.recover(walDir) { WALEntry entry ->
            if (entry.type == WALEntryType.APPEND_POINTS && entry.seriesKey != null) {
                invertedIndex.indexSeries(entry.seriesKey)
                activeMemTable.putAll(entry.seriesKey, entry.points, entry.sequenceNumber)
                totalPointsWritten.addAndGet(entry.points.size())
            }
        }
    }

    @Override
    void close() {
        if (!closed) {
            closed = true
            triggerFlush()
            wal.close()
            for (SegmentReader reader : segmentReaders) {
                reader.close()
            }
            segmentReaders.clear()
        }
    }
}
