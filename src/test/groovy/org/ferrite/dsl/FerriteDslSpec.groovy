package org.ferrite.dsl

import org.ferrite.common.MetricPoint
import org.ferrite.common.TimeSeriesKey
import org.ferrite.query.AggregationType
import org.ferrite.query.QueryPlan
import spock.lang.Specification

class FerriteDslSpec extends Specification {

    def "FerriteDsl should construct declarative QueryPlan via fluent builder"() {
        when:
        QueryPlan plan = FerriteDsl.query {
            from "sys.cpu.usage"
            tag "host", "srv-omega-01"
            tag "datacenter", "us-east-1"
            between 1000L, 50000L
            min 10.0d
            max 95.0d
            rate true
            scale 100.0d
            every "5s", "avg"
        }

        then:
        plan.metricName == "sys.cpu.usage"
        plan.tagFilters.get("host") == "srv-omega-01"
        plan.tagFilters.get("datacenter") == "us-east-1"
        plan.timeRange.fromEpochMs == 1000L
        plan.timeRange.toEpochMs == 50000L
        plan.minThreshold == 10.0d
        plan.maxThreshold == 95.0d
        plan.computeRate == true
        plan.scaleMultiplier == 100.0d
        plan.windowSizeMs == 5000L
        plan.aggregation == AggregationType.AVG
    }

    def "FerriteDsl streaming pipeline should filter, map, and sink telemetry points"() {
        given:
        List<MetricPoint> collected = []

        StreamPipeline pipeline = FerriteDsl.stream {
            filter { it.value > 10.0d }
            map { new MetricPoint(it.timestamp, it.value * 2.0d) }
            sink { collected.add(it) }
        }

        when: "ingesting stream of points"
        pipeline.ingest(new MetricPoint(100L, 5.0d))   // filtered out
        pipeline.ingest(new MetricPoint(200L, 15.0d))  // mapped to 30.0
        pipeline.ingest(new MetricPoint(300L, 25.0d))  // mapped to 50.0

        then:
        pipeline.ingestedCount == 3L
        pipeline.emittedCount == 2L
        collected.size() == 2
        collected[0].value == 30.0d
        collected[1].value == 50.0d
    }

    def "FerriteDsl streaming pipeline should aggregate points in tumbling windows"() {
        given:
        List<MetricPoint> windowResults = []

        StreamPipeline pipeline = FerriteDsl.stream {
            window(1000L) { List<MetricPoint> pts ->
                double sum = 0.0d
                pts.each { sum += it.value }
                new MetricPoint(pts[0].timestamp, sum / (double) pts.size())
            }
            sink { windowResults.add(it) }
        }

        when: "ingesting points across two 1000ms windows"
        pipeline.ingest(new MetricPoint(100L, 10.0d))
        pipeline.ingest(new MetricPoint(200L, 20.0d))
        pipeline.ingest(new MetricPoint(1100L, 40.0d)) // triggers first window emission (avg of 10, 20 = 15)
        pipeline.ingest(new MetricPoint(1200L, 60.0d))
        pipeline.flush()                               // flushes second window (avg of 40, 60 = 50)

        then:
        windowResults.size() == 2
        windowResults[0].value == 15.0d
        windowResults[1].value == 50.0d
    }

    def "AlertRule and AlertManager should trigger and resolve sustained threshold alerts"() {
        given:
        AlertManager manager = new AlertManager()
        List<AlertRule.AlertEvent> alertsTriggered = []

        AlertRule rule = FerriteDsl.alert {
            name "HighCpuUsage"
            metric "node.cpu"
            tag "env", "prod"
            condition { it.value > 85.0d }
            sustainedFor 1000L // 1 second sustained
            severity AlertRule.Severity.CRITICAL
            notify { alertsTriggered.add(it) }
        }
        manager.registerRule(rule)

        TimeSeriesKey key = TimeSeriesKey.parse("node.cpu{env=prod,host=app-1}")

        when: "first point exceeds threshold, alert enters PENDING"
        def e1 = manager.evaluate(new MetricPoint(1000L, 92.0d))

        then: "rule is pending but not firing yet"
        rule.state == AlertRule.State.PENDING
        manager.activeIncidentCount == 0
        alertsTriggered.isEmpty()

        when: "point sustained beyond 1000ms window"
        def e2 = manager.evaluate(new MetricPoint(2200L, 95.0d))

        then: "rule fires critical incident"
        rule.state == AlertRule.State.FIRING
        manager.activeIncidentCount == 1
        alertsTriggered.size() == 1
        alertsTriggered[0].ruleName == "HighCpuUsage"
        alertsTriggered[0].severity == AlertRule.Severity.CRITICAL
        alertsTriggered[0].firstFired == true

        when: "metric value recovers below threshold"
        def e3 = manager.evaluate(new MetricPoint(3000L, 45.0d))

        then: "incident auto-resolves"
        rule.state == AlertRule.State.OK
        manager.activeIncidentCount == 0
        alertsTriggered.size() == 2
        alertsTriggered[1].state == AlertRule.State.OK
    }
}
