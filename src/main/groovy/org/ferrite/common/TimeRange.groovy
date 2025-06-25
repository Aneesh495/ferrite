package org.ferrite.common

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

import java.time.Duration
import java.time.Instant
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Represents an immutable closed time interval [fromEpochMs, toEpochMs].
 * Provides duration calculations, overlap verification, interval splitting, and human-readable parsing.
 */
@CompileStatic
@EqualsAndHashCode
@ToString(includeNames = true)
class TimeRange implements Comparable<TimeRange>, Serializable {
    private static final long serialVersionUID = 1L

    final long fromEpochMs
    final long toEpochMs

    private static final Pattern DURATION_PATTERN = Pattern.compile("(\\d+)([a-zA-Z]+)")

    TimeRange(long fromEpochMs, long toEpochMs) {
        if (toEpochMs < fromEpochMs) {
            throw new IllegalArgumentException("toEpochMs (${toEpochMs}) cannot be earlier than fromEpochMs (${fromEpochMs})")
        }
        this.fromEpochMs = fromEpochMs
        this.toEpochMs = toEpochMs
    }

    static TimeRange between(long fromEpochMs, long toEpochMs) {
        new TimeRange(fromEpochMs, toEpochMs)
    }

    static TimeRange between(Instant from, Instant to) {
        new TimeRange(from.toEpochMilli(), to.toEpochMilli())
    }

    static TimeRange last(Duration duration) {
        long now = System.currentTimeMillis()
        new TimeRange(now - duration.toMillis(), now)
    }

    static TimeRange last(String durationString) {
        last(parseDuration(durationString))
    }

    static TimeRange allTime() {
        new TimeRange(0L, Long.MAX_VALUE)
    }

    long getDurationMs() {
        toEpochMs - fromEpochMs
    }

    Duration getDuration() {
        Duration.ofMillis(getDurationMs())
    }

    boolean contains(long epochMs) {
        epochMs >= fromEpochMs && epochMs <= toEpochMs
    }

    boolean contains(TimeRange other) {
        other != null && this.fromEpochMs <= other.fromEpochMs && this.toEpochMs >= other.toEpochMs
    }

    boolean overlaps(TimeRange other) {
        other != null && this.fromEpochMs <= other.toEpochMs && this.toEpochMs >= other.fromEpochMs
    }

    TimeRange intersect(TimeRange other) {
        if (!overlaps(other)) {
            return null
        }
        long newFrom = Math.max(this.fromEpochMs, other.fromEpochMs)
        long newTo = Math.min(this.toEpochMs, other.toEpochMs)
        new TimeRange(newFrom, newTo)
    }

    List<TimeRange> split(long bucketSizeMs) {
        if (bucketSizeMs <= 0) {
            throw new IllegalArgumentException("bucketSizeMs must be positive: ${bucketSizeMs}")
        }
        List<TimeRange> buckets = new ArrayList<>()
        long current = fromEpochMs
        while (current <= toEpochMs) {
            long next = Math.min(current + bucketSizeMs - 1L, toEpochMs)
            buckets.add(new TimeRange(current, next))
            if (next == Long.MAX_VALUE) break
            current = next + 1L
        }
        buckets
    }

    static Duration parseDuration(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Duration string cannot be null or empty")
        }
        Matcher matcher = DURATION_PATTERN.matcher(text.trim())
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid duration string: ${text}")
        }
        long amount = Long.parseLong(matcher.group(1))
        String unit = matcher.group(2).toLowerCase()
        switch (unit) {
            case "ns":
            case "nanos":
                return Duration.ofNanos(amount)
            case "us":
            case "micros":
                return Duration.ofNanos(amount * 1000L)
            case "ms":
            case "millis":
                return Duration.ofMillis(amount)
            case "s":
            case "sec":
            case "seconds":
                return Duration.ofSeconds(amount)
            case "m":
            case "min":
            case "minutes":
                return Duration.ofMinutes(amount)
            case "h":
            case "hr":
            case "hours":
                return Duration.ofHours(amount)
            case "d":
            case "days":
                return Duration.ofDays(amount)
            default:
                throw new IllegalArgumentException("Unsupported duration unit: ${unit}")
        }
    }

    @Override
    int compareTo(TimeRange o) {
        if (this.fromEpochMs != o.fromEpochMs) {
            return Long.compare(this.fromEpochMs, o.fromEpochMs)
        }
        return Long.compare(this.toEpochMs, o.toEpochMs)
    }
}
