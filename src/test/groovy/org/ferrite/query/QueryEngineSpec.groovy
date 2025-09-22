package org.ferrite.query

import org.ferrite.common.MetricPoint
import org.ferrite.common.TimeRange
import org.ferrite.common.TimeSeriesKey
import org.ferrite.storage.StorageEngine
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

class QueryEngineSpec extends Specification {

    Path tempDir
    StorageEngine engine
    QueryEngine queryEngine

    def setup() {
        tempDir = Files.createTempDirectory("ferrite-query-test")
        engine = new StorageEngine(tempDir, 1024 * 1024, false)
        queryEngine = new QueryEngine(engine)
    }

    def cleanup() {
        engine?.close()
        if (tempDir && Files.exists(tempDir)) {
            tempDir.toFile().deleteDir()
        }
    }

    def "should execute raw time series queries across time range"() {
        given:
        def k1 = TimeSeriesKey.of("cpu.usage", [host: "srv-01"])
        def k2 = TimeSeriesKey.of("cpu.usage", [host: "srv-02"])

        for (int i = 0; i < 20; i++) {
            engine.write(k1, MetricPoint.of((long) (i * 1000), (double) (i * 5)))
            engine.write(k2, MetricPoint.of((long) (i * 1000), (double) (i * 10)))
        }

        when:
        def plan = new QueryPlan(
                "cpu.usage",
                Collections.emptyMap(),
                TimeRange.between(5000L, 10000L),
                null,
                null,
                null,
                null,
                false,
                1.0d
        )
        def res = queryEngine.execute(plan)

        then:
        res.totalSeries() == 2
        res.series.get(k1).size() == 6 // 5000..10000 (inclusive)
        res.series.get(k1)[0].value == 25.0
        res.series.get(k2)[0].value == 50.0
    }

    def "should filter values by min and max thresholds"() {
        given:
        def key = TimeSeriesKey.of("mem.used", [env: "prod"])
        List<Double> values = [10.0, 20.0, 50.0, 75.0, 90.0, 100.0]
        for (int i = 0; i < values.size(); i++) {
            engine.write(key, MetricPoint.of((long) (i * 1000), values[i]))
        }

        when:
        def plan = new QueryPlan(
                "mem.used",
                [env: "prod"],
                TimeRange.allTime(),
                null,
                null,
                30.0d,  // minThreshold
                80.0d,  // maxThreshold
                false,
                1.0d
        )
        def res = queryEngine.execute(plan)

        then:
        res.totalSeries() == 1
        def pts = res.series.get(key)
        pts.size() == 2 // 50.0 and 75.0
        pts[0].value == 50.0
        pts[1].value == 75.0
    }

    def "should aggregate across entire series duration"() {
        given:
        def key = TimeSeriesKey.of("sensor.temp", [room: "lab1"])
        for (int i = 1; i <= 10; i++) {
            engine.write(key, MetricPoint.of((long) (i * 1000), (double) i))
        }

        when:
        def avgRes = queryEngine.executeAggregate(key, TimeRange.allTime(), AggregationType.AVG)
        def sumRes = queryEngine.executeAggregate(key, TimeRange.allTime(), AggregationType.SUM)
        def maxRes = queryEngine.executeAggregate(key, TimeRange.allTime(), AggregationType.MAX)
        def minRes = queryEngine.executeAggregate(key, TimeRange.allTime(), AggregationType.MIN)

        then:
        avgRes.series.get(key)[0].value == 5.5 // (1 + 10) / 2
        sumRes.series.get(key)[0].value == 55.0
        maxRes.series.get(key)[0].value == 10.0
        minRes.series.get(key)[0].value == 1.0
    }

    def "should compute windowed tumbling rollups"() {
        given:
        def key = TimeSeriesKey.of("http.latency", [route: "/checkout"])
        // 30 samples: 3 buckets of 10 samples each (1000ms bucket size)
        for (int i = 0; i < 30; i++) {
            engine.write(key, MetricPoint.of((long) (i * 100), (double) 10.0))
        }

        when:
        def windowRes = queryEngine.executeWindowed(key, TimeRange.allTime(), 1000L, AggregationType.COUNT)

        then:
        def pts = windowRes.series.get(key)
        pts.size() == 3
        pts[0].value == 10.0 // 10 samples in bucket 0..999
        pts[1].value == 10.0 // 10 samples in bucket 1000..1999
        pts[2].value == 10.0 // 10 samples in bucket 2000..2999
    }

    def "should join parallel series along time axis"() {
        given:
        def k1 = TimeSeriesKey.of("network.rx", Collections.emptyMap())
        def k2 = TimeSeriesKey.of("network.tx", Collections.emptyMap())

        engine.write(k1, [
                MetricPoint.of(1000L, 100.0),
                MetricPoint.of(2000L, 200.0),
                MetricPoint.of(3000L, 300.0)
        ])
        engine.write(k2, [
                MetricPoint.of(1000L, 50.0),
                MetricPoint.of(2000L, 80.0)
        ])

        when:
        def op1 = new ScanOperator(engine, k1, TimeRange.allTime())
        def op2 = new ScanOperator(engine, k2, TimeRange.allTime())
        def join = new JoinOperator(op1, op2, 0L)
        join.open()
        def batch = join.next()
        join.close()

        then:
        batch != null
        batch.numRows() == 2 // 1000 and 2000 matched
        batch.doubleColumn("left").getDouble(0) == 100.0
        batch.doubleColumn("right").getDouble(0) == 50.0
        batch.doubleColumn("left").getDouble(1) == 200.0
        batch.doubleColumn("right").getDouble(1) == 80.0
    }
}
