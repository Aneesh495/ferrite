package org.ferrite.common

import spock.lang.Specification

class TimeSeriesKeySpec extends Specification {

    def "should construct canonical time series key and compute deterministic ID"() {
        given:
        def k1 = TimeSeriesKey.of("http_requests_total", [handler: "/api", method: "GET"])
        def k2 = TimeSeriesKey.of("http_requests_total", [method: "GET", handler: "/api"])

        expect:
        k1 == k2
        k1.hashCode() == k2.hashCode()
        k1.seriesId == k2.seriesId
        k1.toCanonicalString() == "http_requests_total{handler=/api,method=GET}"
    }

    def "should parse prometheus style metric key strings"() {
        given:
        String raw = 'node_cpu_seconds_total{cpu="0",mode="idle"}'

        when:
        def key = TimeSeriesKey.parse(raw)

        then:
        key.metricName == "node_cpu_seconds_total"
        key.tags.size() == 2
        key.tags.get("cpu") == "0"
        key.tags.get("mode") == "idle"
        key.toCanonicalString() == "node_cpu_seconds_total{cpu=0,mode=idle}"
    }

    def "should parse metric name without tags"() {
        when:
        def key = TimeSeriesKey.parse("jvm_memory_used_bytes")

        then:
        key.metricName == "jvm_memory_used_bytes"
        key.tags.isEmpty()
        key.toCanonicalString() == "jvm_memory_used_bytes"
    }

    def "should reject invalid syntax"() {
        when:
        TimeSeriesKey.parse("invalid metric{foo}")

        then:
        thrown(IllegalArgumentException)
    }
}
