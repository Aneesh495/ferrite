package org.ferrite.analytics

import spock.lang.Specification

class AnalyticsSpec extends Specification {

    def "TDigest should estimate median and extreme percentiles accurately"() {
        given:
        TDigest digest = new TDigest(100.0d)
        Random rng = new Random(42L)

        when: "adding 1000 uniform samples in [0, 1000]"
        1000.times {
            digest.add(rng.nextDouble() * 1000.0d)
        }

        then: "percentiles match uniform distribution expectations within tolerance"
        digest.size() == 1000L
        Math.abs(digest.quantile(0.5d) - 500.0d) < 50.0d
        Math.abs(digest.quantile(0.99d) - 990.0d) < 25.0d
        Math.abs(digest.quantile(0.01d) - 10.0d) < 25.0d
    }

    def "TDigest should merge two digests correctly"() {
        given:
        TDigest d1 = new TDigest(100.0d)
        TDigest d2 = new TDigest(100.0d)

        when:
        500.times { d1.add(it as double) }
        500.times { d2.add((it + 500) as double) }
        d1.merge(d2)

        then:
        d1.size() == 1000L
        Math.abs(d1.quantile(0.5d) - 500.0d) < 15.0d
    }

    def "HyperLogLog should estimate cardinality within expected standard error"() {
        given: "HLL with 12 bits precision (4096 registers, ~1.6% standard error)"
        HyperLogLog hll = new HyperLogLog(12)
        int uniqueCount = 50000

        when: "adding unique metrics"
        uniqueCount.times { i ->
            hll.offer("metric.host-${i}.cpu")
        }

        then: "estimate is within 3% of actual cardinality"
        long estimate = hll.cardinality()
        double errorPct = Math.abs(estimate - uniqueCount) / (double) uniqueCount
        errorPct < 0.03d
    }

    def "HyperLogLog should support union merge"() {
        given:
        HyperLogLog hll1 = new HyperLogLog(10)
        HyperLogLog hll2 = new HyperLogLog(10)

        when:
        10000.times { hll1.offer("item-${it}") }
        10000.times { hll2.offer("item-${it + 5000}") } // 5000 overlap, total 15000
        hll1.merge(hll2)

        then:
        long estimate = hll1.cardinality()
        double errorPct = Math.abs(estimate - 15000) / 15000.0d
        errorPct < 0.05d
    }

    def "CountMinSketch should bound point query frequencies"() {
        given:
        CountMinSketch cms = new CountMinSketch(0.001d, 0.99d)

        when: "inserting frequencies"
        cms.add("req:login", 500L)
        cms.add("req:search", 2500L)
        cms.add("req:checkout", 50L)

        then: "estimates are greater than or equal to true count"
        cms.estimate("req:login") >= 500L
        cms.estimate("req:search") >= 2500L
        cms.estimate("req:checkout") >= 50L

        and: "rare items have near-zero frequency"
        cms.estimate("req:unknown") < 10L
    }

    def "HoltWinters should forecast linear trend accurately"() {
        given:
        HoltWinters hw = new HoltWinters(0.5d, 0.3d)
        double[] linearData = new double[50]
        for (int i = 0; i < 50; i++) {
            linearData[i] = 10.0d + 2.5d * (double) i
        }

        when:
        hw.fit(linearData)
        double next1 = hw.forecast(1)
        double next5 = hw.forecast(5)

        then: "next step should be approximately 10 + 2.5 * 50 = 135"
        Math.abs(next1 - 135.0d) < 1.0d
        Math.abs(next5 - 145.0d) < 2.0d
    }

    def "HoltWinters should detect injected anomalies"() {
        given:
        HoltWinters hw = new HoltWinters(0.3d, 0.1d)
        for (int i = 0; i < 30; i++) {
            hw.update(100.0d + (i % 2 == 0 ? 1.0d : -1.0d))
        }

        expect:
        !hw.isAnomaly(100.5d)
        hw.isAnomaly(500.0d, 3.0d) // massive spike
    }

    def "CUSUMDetector should signal persistent mean shifts"() {
        given:
        CUSUMDetector detector = new CUSUMDetector(1.0d, 8.0d, 50.0d, false)

        when: "ingesting baseline data around mean 50"
        boolean normalAlarm = false
        20.times {
            if (detector.update(50.0d + (it % 2 == 0 ? 0.5d : -0.5d))) {
                normalAlarm = true
            }
        }

        then: "no alarm raised during normal operation"
        !normalAlarm

        when: "a persistent shift to 65 occurs"
        boolean shiftDetected = false
        15.times {
            if (detector.update(65.0d)) {
                shiftDetected = true
            }
        }

        then: "alarm is raised"
        shiftDetected
    }

    def "EWMABands should adapt volatility and flag spikes"() {
        given:
        EWMABands bands = new EWMABands(0.2d, 0.1d, 3.0d)

        when: "feeding steady signal"
        30.times { bands.update(10.0d) }

        then:
        Math.abs(bands.getMean() - 10.0d) < 0.1d

        and: "a sharp spike breaches bands"
        bands.update(50.0d) == true
    }
}
