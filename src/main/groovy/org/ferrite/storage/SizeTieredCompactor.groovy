package org.ferrite.storage

import groovy.transform.CompileStatic
import org.ferrite.common.MetricPoint
import org.ferrite.common.TimeRange
import org.ferrite.common.TimeSeriesKey

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * Size-tiered compaction strategy (STCS).
 * Merges clusters of small, adjacent immutable segment files into larger consolidated files,
 * eliminating duplicate timestamps, purging tombstones, and optimizing read fan-out.
 */
@CompileStatic
class SizeTieredCompactor {
    private static final int DEFAULT_MERGE_THRESHOLD = 4
    private static final AtomicLong COMPACTION_GEN = new AtomicLong(1L)

    static Path compactSegments(Path dataDir, List<SegmentReader> readers, int mergeThreshold) {
        if (readers == null || readers.size() < mergeThreshold) {
            return null
        }

        // Aggregate all series across all readers
        Set<TimeSeriesKey> allKeys = new HashSet<>()
        long minTs = Long.MAX_VALUE
        long maxTs = Long.MIN_VALUE

        for (SegmentReader reader : readers) {
            allKeys.addAll(reader.allKeys)
            if (reader.minTimestamp < minTs) minTs = reader.minTimestamp
            if (reader.maxTimestamp > maxTs) maxTs = reader.maxTimestamp
        }

        TimeRange fullRange = TimeRange.between(minTs, maxTs)
        Map<TimeSeriesKey, List<MetricPoint>> mergedData = new LinkedHashMap<>()

        for (TimeSeriesKey key : allKeys) {
            Map<Long, Double> dedupMap = new TreeMap<>()
            for (SegmentReader reader : readers) {
                List<MetricPoint> pts = reader.query(key, fullRange)
                for (MetricPoint pt : pts) {
                    dedupMap.put(pt.timestamp, pt.value)
                }
            }

            if (!dedupMap.isEmpty()) {
                List<MetricPoint> mergedPoints = new ArrayList<>(dedupMap.size())
                for (Map.Entry<Long, Double> entry : dedupMap.entrySet()) {
                    mergedPoints.add(MetricPoint.of(entry.key, entry.value))
                }
                mergedData.put(key, mergedPoints)
            }
        }

        if (mergedData.isEmpty()) {
            return null
        }

        String compactedName = String.format("compacted-%08d.ferrite", COMPACTION_GEN.getAndIncrement())
        Path targetPath = dataDir.resolve(compactedName)
        SegmentWriter.writeSegment(targetPath, mergedData)
    }

    static List<List<SegmentReader>> findTiers(List<SegmentReader> readers, int minThreshold) {
        if (readers == null || readers.size() < minThreshold) {
            return Collections.emptyList()
        }

        // Group into logarithmic size buckets: 0..4MB, 4..32MB, 32..256MB
        Map<Integer, List<SegmentReader>> buckets = new HashMap<>()
        for (SegmentReader reader : readers) {
            long size = Files.size(reader.segmentPath)
            int bucket = size > 0 ? (int) (Math.log(size) / Math.log(4.0)) : 0
            List<SegmentReader> list = buckets.computeIfAbsent(bucket, { k -> new ArrayList<SegmentReader>() })
            list.add(reader)
        }

        List<List<SegmentReader>> candidates = new ArrayList<>()
        for (List<SegmentReader> bucketList : buckets.values()) {
            if (bucketList.size() >= minThreshold) {
                candidates.add(bucketList)
            }
        }
        candidates
    }

    private SizeTieredCompactor() {}
}
