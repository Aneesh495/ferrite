package org.ferrite.common

import spock.lang.Specification

class MetricPointSpec extends Specification {

    def "should create standard double metric point"() {
        given:
        long ts = 1700000000000L
        double val = 42.125

        when:
        def point = MetricPoint.of(ts, val)

        then:
        point.timestamp == ts
        point.value == val
        point.valueType == ValueType.DOUBLE
        !point.isAuxiliaryPresent()
    }

    def "should handle long value metric point"() {
        given:
        long ts = 1700000000000L
        long val = 9876543210L

        when:
        def point = MetricPoint.of(ts, val)

        then:
        point.timestamp == ts
        point.value == (double) val
        point.longValue == val
        point.valueType == ValueType.LONG
    }

    def "should support auxiliary fields"() {
        given:
        long ts = 1700000000000L
        def aux = [status: 200, message: "OK"]

        when:
        def point = MetricPoint.of(ts, 1.0, aux)

        then:
        point.isAuxiliaryPresent()
        point.getField("status") == 200
        point.getField("message") == "OK"
    }

    def "should sort metric points by timestamp then value"() {
        given:
        def p1 = MetricPoint.of(100L, 5.0)
        def p2 = MetricPoint.of(100L, 10.0)
        def p3 = MetricPoint.of(200L, 1.0)

        expect:
        p1 < p2
        p2 < p3
    }
}
