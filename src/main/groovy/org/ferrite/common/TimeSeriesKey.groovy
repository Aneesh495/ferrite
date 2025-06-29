package org.ferrite.common

import groovy.transform.CompileStatic

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Immutable composite key representing a single unique time series identity.
 * Combines metric name and canonically sorted tag set into a high-entropy 64-bit series ID.
 */
@CompileStatic
class TimeSeriesKey implements Comparable<TimeSeriesKey>, Serializable {
    private static final long serialVersionUID = 1L

    private static final Pattern PROMETHEUS_SYNTAX = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\\{(.*)\\})?\$")

    final String metricName
    final TagSet tags
    final long seriesId
    private String stringRepresentationCache

    TimeSeriesKey(String metricName, TagSet tags) {
        if (metricName == null || metricName.trim().isEmpty()) {
            throw new IllegalArgumentException("Metric name cannot be null or empty")
        }
        this.metricName = metricName.trim()
        this.tags = tags != null ? tags : TagSet.EMPTY
        this.seriesId = computeSeriesId(this.metricName, this.tags)
    }

    static TimeSeriesKey of(String metricName, Map<String, String> tags) {
        new TimeSeriesKey(metricName, TagSet.of(tags))
    }

    static TimeSeriesKey of(String metricName, TagSet tags) {
        new TimeSeriesKey(metricName, tags)
    }

    static TimeSeriesKey parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Input string cannot be null or empty")
        }
        String trimmed = text.trim()
        Matcher matcher = PROMETHEUS_SYNTAX.matcher(trimmed)
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid metric key syntax: ${text}")
        }
        String metric = matcher.group(1)
        String tagBody = matcher.group(2)
        if (tagBody == null || tagBody.trim().isEmpty()) {
            return new TimeSeriesKey(metric, TagSet.EMPTY)
        }

        Map<String, String> tagMap = new HashMap<>()
        String[] pairs = tagBody.split(",")
        for (String pair : pairs) {
            String p = pair.trim()
            if (p.isEmpty()) continue
            int eq = p.indexOf("=")
            if (eq <= 0 || eq >= p.length() - 1) {
                throw new IllegalArgumentException("Invalid tag pair in key: ${pair}")
            }
            String k = p.substring(0, eq).trim()
            String v = p.substring(eq + 1).trim()
            if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
                v = v.substring(1, v.length() - 1)
            }
            tagMap.put(k, v)
        }
        new TimeSeriesKey(metric, TagSet.of(tagMap))
    }

    private static long computeSeriesId(String metric, TagSet tagSet) {
        long h = 1125899906842597L // FNV offset basis
        for (int i = 0; i < metric.length(); i++) {
            h ^= (long) metric.charAt(i)
            h *= 1099511628211L
        }
        for (int i = 0; i < tagSet.size(); i++) {
            String k = tagSet.getKeyAt(i)
            String v = tagSet.getValueAt(i)
            h ^= 0x5555555555555555L
            for (int j = 0; j < k.length(); j++) {
                h ^= (long) k.charAt(j)
                h *= 1099511628211L
            }
            h ^= ~0x5555555555555555L
            for (int j = 0; j < v.length(); j++) {
                h ^= (long) v.charAt(j)
                h *= 1099511628211L
            }
        }
        h
    }

    String toCanonicalString() {
        if (stringRepresentationCache == null) {
            if (tags.isEmpty()) {
                stringRepresentationCache = metricName
            } else {
                stringRepresentationCache = metricName + "{" + tags.toCanonicalString() + "}"
            }
        }
        stringRepresentationCache
    }

    @Override
    int compareTo(TimeSeriesKey o) {
        int cmp = this.metricName.compareTo(o.metricName)
        if (cmp != 0) return cmp
        this.tags.compareTo(o.tags)
    }

    @Override
    boolean equals(Object o) {
        if (this.is(o)) return true
        if (!(o instanceof TimeSeriesKey)) return false
        TimeSeriesKey other = (TimeSeriesKey) o
        if (this.seriesId != other.seriesId) return false
        return this.metricName.equals(other.metricName) && this.tags.equals(other.tags)
    }

    @Override
    int hashCode() {
        (int) (seriesId ^ (seriesId >>> 32))
    }

    @Override
    String toString() {
        toCanonicalString()
    }
}
