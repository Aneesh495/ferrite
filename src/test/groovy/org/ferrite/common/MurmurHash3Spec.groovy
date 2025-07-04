package org.ferrite.common

import spock.lang.Specification
import java.nio.charset.StandardCharsets

class MurmurHash3Spec extends Specification {

    def "should hash 32-bit deterministically"() {
        given:
        String text = "ferrite-metric-stream"

        when:
        int h1 = MurmurHash3.hash32(text)
        int h2 = MurmurHash3.hash32(text)

        then:
        h1 == h2
        h1 != 0
    }

    def "should compute 64-bit and 128-bit hashes with high entropy"() {
        given:
        String k1 = "metric.cpu.usage{host=srv01}"
        String k2 = "metric.cpu.usage{host=srv02}"

        when:
        long hash1 = MurmurHash3.hash64(k1)
        long hash2 = MurmurHash3.hash64(k2)
        long[] h128 = MurmurHash3.hash128(k1.getBytes(StandardCharsets.UTF_8), 0, k1.length(), 42L)

        then:
        hash1 != hash2
        h128.length == 2
        h128[0] != 0
        h128[1] != 0
    }

    def "should handle empty byte array"() {
        expect:
        MurmurHash3.hash32(new byte[0], 0, 0, 0) == 0
        MurmurHash3.hash64(new byte[0], 0, 0, 0L) == 0L
    }
}
