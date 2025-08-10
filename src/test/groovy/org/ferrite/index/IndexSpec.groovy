package org.ferrite.index

import org.ferrite.common.TimeSeriesKey
import spock.lang.Specification

class IndexSpec extends Specification {

    def "should test roaring bitmap sparse and dense transitions with set operations"() {
        given:
        def bm1 = new RoaringBitmap()
        def bm2 = new RoaringBitmap()

        when:
        // Sparse values
        [1, 10, 100, 1000, 5000].each { bm1.add(it) }
        // Dense values crossing 4096 threshold
        for (int i = 0; i < 5000; i++) {
            bm2.add(i * 2)
        }

        then:
        bm1.cardinality() == 5
        bm2.cardinality() == 5000
        bm1.contains(10)
        !bm1.contains(11)

        when:
        def intersection = bm1.and(bm2)
        def union = bm1.or(bm2)

        then:
        // bm1 has 1, 10, 100, 1000, 5000; bm2 has even numbers 0..9998
        // Evens in bm1: 10, 100, 1000, 5000 (4 items)
        intersection.cardinality() == 4
        intersection.contains(10)
        intersection.contains(5000)
        !intersection.contains(1)

        union.contains(1)
        union.contains(2)
        union.cardinality() == 5001 // 5000 + 1 (odd number 1)
    }

    def "should test bloom filter zero false negatives and low false positive rate"() {
        given:
        int n = 5000
        double targetFpp = 0.01 // 1%
        def filter = new BloomFilter(n, targetFpp)

        when:
        for (int i = 0; i < n; i++) {
            filter.add("metric.key." + i)
        }

        then:
        // Zero false negatives guaranteed
        for (int i = 0; i < n; i++) {
            assert filter.mightContain("metric.key." + i)
        }

        // Measure false positives on disjoint keys
        int falsePositives = 0
        int testCount = 5000
        for (int i = n; i < n + testCount; i++) {
            if (filter.mightContain("metric.key." + i)) {
                falsePositives++
            }
        }
        double empiricalFpp = falsePositives / (double) testCount
        empiricalFpp <= 0.03 // Well within probabilistic tolerances
    }

    def "should test radix tree prefix search"() {
        given:
        def tree = new RadixTree<String>()
        tree.put("cpu.usage.user", "user_metric")
        tree.put("cpu.usage.system", "sys_metric")
        tree.put("cpu.idle", "idle_metric")
        tree.put("memory.used", "mem_metric")

        expect:
        tree.size() == 4
        tree.get("cpu.usage.user") == "user_metric"
        tree.get("nonexistent") == null

        when:
        def cpuUsageMatches = tree.scanPrefix("cpu.usage")
        def memMatches = tree.scanPrefix("memory.")

        then:
        cpuUsageMatches.size() == 2
        cpuUsageMatches.collect { it.key }.sort() == ["cpu.usage.system", "cpu.usage.user"]
        memMatches.size() == 1
        memMatches[0].key == "memory.used"
    }

    def "should test multi-dimensional inverted index tag search"() {
        given:
        def index = new InvertedIndex()
        def k1 = TimeSeriesKey.of("cpu.usage", [env: "prod", host: "web-01", region: "us-east"])
        def k2 = TimeSeriesKey.of("cpu.usage", [env: "prod", host: "web-02", region: "us-east"])
        def k3 = TimeSeriesKey.of("cpu.usage", [env: "staging", host: "web-03", region: "us-west"])
        def k4 = TimeSeriesKey.of("disk.free", [env: "prod", host: "db-01", region: "us-east"])

        when:
        index.indexSeries(k1)
        index.indexSeries(k2)
        index.indexSeries(k3)
        index.indexSeries(k4)

        then:
        index.totalSeriesCount() == 4

        when:
        def prodCpuUsEast = index.search("cpu.usage", [env: "prod", region: "us-east"])
        def allDisk = index.search("disk.free", Collections.emptyMap())
        def stagingWest = index.search(null, [region: "us-west"])
        def noMatch = index.search("cpu.usage", [env: "nonexistent"])

        then:
        prodCpuUsEast.size() == 2
        prodCpuUsEast.contains(k1)
        prodCpuUsEast.contains(k2)

        allDisk.size() == 1
        allDisk[0] == k4

        stagingWest.size() == 1
        stagingWest[0] == k3

        noMatch.isEmpty()
    }
}
