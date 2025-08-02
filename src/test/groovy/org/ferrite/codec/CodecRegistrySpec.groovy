package org.ferrite.codec

import spock.lang.Specification

class CodecRegistrySpec extends Specification {

    def "should compress and decompress timestamp arrays via registry"() {
        given:
        int n = 100
        long[] ts = new long[n]
        for (int i = 0; i < n; i++) {
            ts[i] = 1700000000000L + i * 1000L
        }

        when:
        byte[] compressed = CodecRegistry.compressTimestamps(ts)
        long[] restored = CodecRegistry.decompressTimestamps(compressed, n)

        then:
        compressed.length < 50
        restored == ts
    }

    def "should compress and decompress double value arrays via registry"() {
        given:
        int n = 100
        double[] vals = new double[n]
        for (int i = 0; i < n; i++) {
            vals[i] = 100.0 + (i % 3) * 0.5
        }

        when:
        byte[] compressed = CodecRegistry.compressValues(vals)
        double[] restored = CodecRegistry.decompressValues(compressed, n)

        then:
        compressed.length < 150
        for (int i = 0; i < n; i++) {
            assert Double.doubleToRawLongBits(restored[i]) == Double.doubleToRawLongBits(vals[i])
        }
    }
}
