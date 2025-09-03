package org.ferrite.storage

import groovy.transform.CompileStatic
import org.ferrite.common.MetricPoint
import org.ferrite.common.TimeRange
import org.ferrite.common.TimeSeriesKey

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory active write buffer.
 * Organizes timestamped series data into concurrent skiplist structures for lock-free
 * concurrent ingestion and ordered range scans.
 */
@CompileStatic
class MemTable {
    private final ConcurrentHashMap<TimeSeriesKey, ConcurrentSkipListMap<Long, Double>> seriesData
    private final AtomicLong pointCount
    private final AtomicLong approximateSizeBytes
    private final AtomicLong minSequence
    private final AtomicLong maxSequence
    private final AtomicBoolean readOnly

    MemTable() {
        this.seriesData = new ConcurrentHashMap<TimeSeriesKey, ConcurrentSkipListMap<Long, Double>>()
        this.pointCount = new AtomicLong(0L)
        this.approximateSizeBytes = new AtomicLong(0L)
        this.minSequence = new AtomicLong(Long.MAX_VALUE)
        this.maxSequence = new AtomicLong(0L)
        this.readOnly = new AtomicBoolean(false)
    }

    void put(TimeSeriesKey key, MetricPoint point, long sequenceNumber) {
        if (readOnly.get()) {
            throw new IllegalStateException("Cannot write to read-only MemTable")
        }
        if (key == null || point == null) return

        ConcurrentSkipListMap<Long, Double> points = getOrCreateMap(key)
        Double prev = points.put(point.timestamp, point.value)
        if (prev == null) {
            pointCount.incrementAndGet()
            approximateSizeBytes.addAndGet(32L) // ~32 bytes per node overhead in skip list
        }

        updateSequences(sequenceNumber)
    }

    void putAll(TimeSeriesKey key, List<MetricPoint> points, long sequenceNumber) {
        if (readOnly.get()) {
            throw new IllegalStateException("Cannot write to read-only MemTable")
        }
        if (key == null || points == null || points.isEmpty()) return

        ConcurrentSkipListMap<Long, Double> map = getOrCreateMap(key)
        for (int i = 0; i < points.size(); i++) {
            MetricPoint pt = points.get(i)
            Double prev = map.put(pt.timestamp, pt.value)
            if (prev == null) {
                pointCount.incrementAndGet()
                approximateSizeBytes.addAndGet(32L)
            }
        }

        updateSequences(sequenceNumber)
    }

    private ConcurrentSkipListMap<Long, Double> getOrCreateMap(TimeSeriesKey key) {
        ConcurrentSkipListMap<Long, Double> map = seriesData.get(key)
        if (map == null) {
            ConcurrentSkipListMap<Long, Double> newMap = new ConcurrentSkipListMap<Long, Double>()
            ConcurrentSkipListMap<Long, Double> existing = seriesData.putIfAbsent(key, newMap)
            if (existing == null) {
                map = newMap
                approximateSizeBytes.addAndGet((long) (key.toCanonicalString().length() * 2 + 64))
            } else {
                map = existing
            }
        }
        map
    }

    List<MetricPoint> query(TimeSeriesKey key, TimeRange range) {
        ConcurrentSkipListMap<Long, Double> map = seriesData.get(key)
        if (map == null || map.isEmpty()) {
            return Collections.emptyList()
        }

        NavigableMap<Long, Double> sub = map.subMap(range.fromEpochMs, true, range.toEpochMs, true)
        if (sub.isEmpty()) {
            return Collections.emptyList()
        }

        List<MetricPoint> result = new ArrayList<>(sub.size())
        for (Map.Entry<Long, Double> entry : sub.entrySet()) {
            result.add(MetricPoint.of(entry.key, entry.value))
        }
        result
    }

    Set<TimeSeriesKey> getAllKeys() {
        Collections.unmodifiableSet(seriesData.keySet())
    }

    Map<TimeSeriesKey, List<MetricPoint>> dumpAll() {
        Map<TimeSeriesKey, List<MetricPoint>> dump = new LinkedHashMap<>()
        for (Map.Entry<TimeSeriesKey, ConcurrentSkipListMap<Long, Double>> entry : seriesData.entrySet()) {
            ConcurrentSkipListMap<Long, Double> map = entry.value
            List<MetricPoint> pts = new ArrayList<>(map.size())
            for (Map.Entry<Long, Double> ptEntry : map.entrySet()) {
                pts.add(MetricPoint.of(ptEntry.key, ptEntry.value))
            }
            dump.put(entry.key, pts)
        }
        dump
    }

    void makeReadOnly() {
        readOnly.set(true)
    }

    boolean isReadOnly() {
        readOnly.get()
    }

    long getPointCount() {
        pointCount.get()
    }

    long getApproximateSizeBytes() {
        approximateSizeBytes.get()
    }

    long getMinSequence() {
        minSequence.get() == Long.MAX_VALUE ? 0L : minSequence.get()
    }

    long getMaxSequence() {
        maxSequence.get()
    }

    private void updateSequences(long seq) {
        if (seq <= 0L) return
        long currentMin = minSequence.get()
        while (seq < currentMin) {
            if (minSequence.compareAndSet(currentMin, seq)) break
            currentMin = minSequence.get()
        }

        long currentMax = maxSequence.get()
        while (seq > currentMax) {
            if (maxSequence.compareAndSet(currentMax, seq)) break
            currentMax = maxSequence.get()
        }
    }
}
