package org.ferrite.storage

import groovy.transform.CompileStatic
import org.ferrite.common.MetricPoint
import org.ferrite.common.TimeRange
import org.ferrite.common.TimeSeriesKey

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * Time-Window Compaction Strategy (TWCS).
 * Partitions historical segments into discrete time windows (e.g. hourly or daily boundaries).
 * Once a time window has closed and no new out-of-order writes arrive, all candidate segments
 * within that window are compacted into a single immutable historical archive.
 */
@CompileStatic
class TimeWindowCompactor {
    private static final AtomicLong WINDOW_GEN = new AtomicLong(1L)

    static Map<Long, List<SegmentReader>> partitionByWindow(List<SegmentReader> readers, long windowSizeMs) {
        if (readers == null || readers.isEmpty() || windowSizeMs <= 0L) {
            return Collections.emptyMap()
        }

        Map<Long, List<SegmentReader>> windows = new HashMap<>()
        for (SegmentReader reader : readers) {
            long windowIndex = (long) (reader.minTimestamp / (double) windowSizeMs)
            List<SegmentReader> list = windows.computeIfAbsent(windowIndex, { k -> new ArrayList<SegmentReader>() })
            list.add(reader)
        }
        windows
    }

    static Path compactWindow(Path dataDir, long windowIndex, List<SegmentReader> readers, long windowSizeMs) {
        if (readers == null || readers.size() < 2) {
            return null
        }

        long windowStart = windowIndex * windowSizeMs
        long windowEnd = windowStart + windowSizeMs - 1L
        TimeRange windowRange = TimeRange.between(windowStart, windowEnd)

        Set<TimeSeriesKey> allKeys = new HashSet<>()
        for (SegmentReader reader : readers) {
            allKeys.addAll(reader.allKeys)
        }

        Map<TimeSeriesKey, List<MetricPoint>> mergedData = new LinkedHashMap<>()
        for (TimeSeriesKey key : allKeys) {
            Map<Long, Double> dedupMap = new TreeMap<>()
            for (SegmentReader reader : readers) {
                List<MetricPoint> pts = reader.query(key, windowRange)
                for (MetricPoint pt : pts) {
                    dedupMap.put(pt.timestamp, pt.value)
                }
            }

            if (!dedupMap.isEmpty()) {
                List<MetricPoint> points = new ArrayList<>(dedupMap.size())
                for (Map.Entry<Long, Double> entry : dedupMap.entrySet()) {
                    points.add(MetricPoint.of(entry.key, entry.value))
                }
                mergedData.put(key, points)
            }
        }

        if (mergedData.isEmpty()) {
            return null
        }

        String fileName = String.format("window-%d-gen%04d.ferrite", windowIndex, WINDOW_GEN.getAndIncrement())
        Path targetPath = dataDir.resolve(fileName)
        SegmentWriter.writeSegment(targetPath, mergedData)
    }

    private TimeWindowCompactor() {}
}
