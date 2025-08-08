package org.ferrite.index

import groovy.transform.CompileStatic
import org.ferrite.common.TagSet
import org.ferrite.common.TimeSeriesKey

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Multi-dimensional inverted index mapping metric names and key-value tag dimensions
 * to compact RoaringBitmap postings lists of series identifiers.
 */
@CompileStatic
class InvertedIndex {
    private final Map<String, Map<String, RoaringBitmap>> tagIndex = new ConcurrentHashMap<>()
    private final Map<String, RoaringBitmap> metricIndex = new ConcurrentHashMap<>()
    private final Map<TimeSeriesKey, Integer> seriesToId = new ConcurrentHashMap<>()
    private final Map<Integer, TimeSeriesKey> idToSeries = new ConcurrentHashMap<>()
    private final AtomicInteger nextInternalId = new AtomicInteger(1)

    synchronized int indexSeries(TimeSeriesKey key) {
        if (key == null) return -1
        Integer existing = seriesToId.get(key)
        if (existing != null) {
            return existing.intValue()
        }

        int id = nextInternalId.getAndIncrement()
        seriesToId.put(key, id)
        idToSeries.put(id, key)

        // Index metric name
        RoaringBitmap metricPosting = metricIndex.computeIfAbsent(key.metricName, { k -> new RoaringBitmap() })
        metricPosting.add(id)

        // Index each tag
        TagSet tags = key.tags
        for (int i = 0; i < tags.size(); i++) {
            String tagK = tags.getKeyAt(i)
            String tagV = tags.getValueAt(i)

            Map<String, RoaringBitmap> valueMap = tagIndex.computeIfAbsent(tagK, { k -> new ConcurrentHashMap<String, RoaringBitmap>() })
            RoaringBitmap tagPosting = valueMap.computeIfAbsent(tagV, { v -> new RoaringBitmap() })
            tagPosting.add(id)
        }

        id
    }

    TimeSeriesKey getSeriesKey(int id) {
        idToSeries.get(id)
    }

    Integer getSeriesId(TimeSeriesKey key) {
        seriesToId.get(key)
    }

    int totalSeriesCount() {
        seriesToId.size()
    }

    List<TimeSeriesKey> search(String metricName, Map<String, String> tagFilters) {
        RoaringBitmap resultPosting = null

        if (metricName != null && !metricName.isEmpty()) {
            RoaringBitmap m = metricIndex.get(metricName)
            if (m == null || m.isEmpty()) {
                return Collections.emptyList()
            }
            resultPosting = m
        }

        if (tagFilters != null && !tagFilters.isEmpty()) {
            for (Map.Entry<String, String> entry : tagFilters.entrySet()) {
                String k = entry.key
                String v = entry.value

                Map<String, RoaringBitmap> valueMap = tagIndex.get(k)
                if (valueMap == null) return Collections.emptyList()
                RoaringBitmap posting = valueMap.get(v)
                if (posting == null || posting.isEmpty()) return Collections.emptyList()

                if (resultPosting == null) {
                    resultPosting = posting
                } else {
                    resultPosting = resultPosting.and(posting)
                    if (resultPosting.isEmpty()) {
                        return Collections.emptyList()
                    }
                }
            }
        }

        if (resultPosting == null) {
            return new ArrayList<>(seriesToId.keySet())
        }

        int[] ids = resultPosting.toArray()
        List<TimeSeriesKey> matched = new ArrayList<>(ids.length)
        for (int id : ids) {
            TimeSeriesKey k = idToSeries.get(id)
            if (k != null) {
                matched.add(k)
            }
        }
        matched
    }

    Set<String> getTagValues(String tagKey) {
        Map<String, RoaringBitmap> valueMap = tagIndex.get(tagKey)
        if (valueMap == null) return Collections.emptySet()
        Collections.unmodifiableSet(valueMap.keySet())
    }

    Set<String> getAllTagKeys() {
        Collections.unmodifiableSet(tagIndex.keySet())
    }

    Set<String> getAllMetricNames() {
        Collections.unmodifiableSet(metricIndex.keySet())
    }
}
