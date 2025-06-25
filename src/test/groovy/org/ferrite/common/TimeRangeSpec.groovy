package org.ferrite.common

import spock.lang.Specification
import java.time.Duration

class TimeRangeSpec extends Specification {

    def "should create valid time range and compute duration"() {
        given:
        long start = 1000L
        long end = 5000L

        when:
        def range = TimeRange.between(start, end)

        then:
        range.fromEpochMs == 1000L
        range.toEpochMs == 5000L
        range.durationMs == 4000L
        range.duration == Duration.ofMillis(4000L)
    }

    def "should reject inverted time range"() {
        when:
        TimeRange.between(5000L, 1000L)

        then:
        thrown(IllegalArgumentException)
    }

    def "should test containment correctly"() {
        given:
        def outer = TimeRange.between(100L, 500L)
        def inner = TimeRange.between(200L, 400L)
        def disjoint = TimeRange.between(600L, 700L)

        expect:
        outer.contains(100L)
        outer.contains(300L)
        outer.contains(500L)
        !outer.contains(99L)
        !outer.contains(501L)
        outer.contains(inner)
        !outer.contains(disjoint)
    }

    def "should test overlaps and intersection"() {
        given:
        def r1 = TimeRange.between(100L, 300L)
        def r2 = TimeRange.between(200L, 400L)
        def r3 = TimeRange.between(500L, 600L)

        expect:
        r1.overlaps(r2)
        r2.overlaps(r1)
        !r1.overlaps(r3)
        r1.intersect(r2) == TimeRange.between(200L, 300L)
        r1.intersect(r3) == null
    }

    def "should parse human readable durations"() {
        expect:
        TimeRange.parseDuration("100ms") == Duration.ofMillis(100)
        TimeRange.parseDuration("15s") == Duration.ofSeconds(15)
        TimeRange.parseDuration("5m") == Duration.ofMinutes(5)
        TimeRange.parseDuration("2h") == Duration.ofHours(2)
        TimeRange.parseDuration("7d") == Duration.ofDays(7)
    }

    def "should split time range into uniform buckets"() {
        given:
        def range = TimeRange.between(0L, 99L)

        when:
        def buckets = range.split(25L)

        then:
        buckets.size() == 4
        buckets[0] == TimeRange.between(0L, 24L)
        buckets[1] == TimeRange.between(25L, 49L)
        buckets[2] == TimeRange.between(50L, 74L)
        buckets[3] == TimeRange.between(75L, 99L)
    }
}
