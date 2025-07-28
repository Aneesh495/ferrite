package org.ferrite.codec

import org.ferrite.bitstream.BitReader
import org.ferrite.bitstream.BitWriter
import spock.lang.Specification

class GorillaCodecSpec extends Specification {

    def "should compress constant interval timestamps down to single bits"() {
        given:
        int n = 1000
        long start = 1700000000000L
        long step = 10000L // 10 seconds

        def writer = new BitWriter()
        def encoder = new GorillaTimestampEncoder(writer)

        when:
        for (int i = 0; i < n; i++) {
            encoder.encode(start + i * step)
        }
        encoder.flush()

        byte[] compressed = writer.toByteArray()
        def reader = new BitReader(compressed)
        def decoder = new GorillaTimestampDecoder(reader)
        long[] decoded = decoder.decodeAll(n)

        then:
        // First 64 bits (timestamp) + 32 bits (delta) + 998 * 1 bit (DOD == 0) = 1094 bits = 137 bytes!
        // Uncompressed 1000 * 8 = 8000 bytes. Compression ratio > 50x!
        compressed.length <= 145
        for (int i = 0; i < n; i++) {
            assert decoded[i] == start + i * step
        }
    }

    def "should handle variable jitter timestamps losslessly"() {
        given:
        List<Long> timestamps = [
                1000L, 1010L, 1020L, 1032L, 1045L, 1050L, 1060L, 1200L, 1210L, 2000L
        ]

        def writer = new BitWriter()
        def encoder = new GorillaTimestampEncoder(writer)
        timestamps.each { encoder.encode(it) }
        encoder.flush()

        when:
        def reader = new BitReader(writer.toByteArray())
        def decoder = new GorillaTimestampDecoder(reader)
        long[] decoded = decoder.decodeAll(timestamps.size())

        then:
        decoded.toList() == timestamps
    }

    def "should compress constant values down to single bits"() {
        given:
        int n = 1000
        double val = 42.125

        def writer = new BitWriter()
        def encoder = new GorillaValueEncoder(writer)

        when:
        for (int i = 0; i < n; i++) {
            encoder.encode(val)
        }
        encoder.flush()

        byte[] compressed = writer.toByteArray()
        def reader = new BitReader(compressed)
        def decoder = new GorillaValueDecoder(reader)
        double[] decoded = decoder.decodeAll(n)

        then:
        // First 64 bits + 999 * 1 bit = 1063 bits = 133 bytes!
        // Uncompressed 8000 bytes. Compression ratio > 50x!
        compressed.length <= 145
        for (int i = 0; i < n; i++) {
            assert Double.doubleToRawLongBits(decoded[i]) == Double.doubleToRawLongBits(val)
        }
    }

    def "should compress realistic telemetry series with high ratio"() {
        given:
        int n = 500
        double[] telemetry = new double[n]
        double current = 72.0
        for (int i = 0; i < n; i++) {
            // Realistic sensor drift with steady periods
            if (i % 10 == 0) {
                current += 0.25
            }
            telemetry[i] = current
        }

        def writer = new BitWriter()
        def encoder = new GorillaValueEncoder(writer)

        when:
        for (int i = 0; i < n; i++) {
            encoder.encode(telemetry[i])
        }
        encoder.flush()

        byte[] compressed = writer.toByteArray()
        def reader = new BitReader(compressed)
        def decoder = new GorillaValueDecoder(reader)
        double[] decoded = decoder.decodeAll(n)

        then:
        // 500 * 8 = 4000 bytes uncompressed; compressed under 300 bytes (> 13x ratio)
        compressed.length < 300
        for (int i = 0; i < n; i++) {
            assert Double.doubleToRawLongBits(decoded[i]) == Double.doubleToRawLongBits(telemetry[i])
        }
    }

    def "should achieve lossless round trip on sine wave and noisy metrics"() {
        given:
        int n = 500
        double[] values = new double[n]
        for (int i = 0; i < n; i++) {
            values[i] = Math.sin(i * 0.1) * 50.0 + (i % 5) * 0.25
        }

        def writer = new BitWriter()
        def encoder = new GorillaValueEncoder(writer)

        when:
        for (int i = 0; i < n; i++) {
            encoder.encode(values[i])
        }
        encoder.flush()

        byte[] compressed = writer.toByteArray()
        def reader = new BitReader(compressed)
        def decoder = new GorillaValueDecoder(reader)
        double[] decoded = decoder.decodeAll(n)

        then:
        for (int i = 0; i < n; i++) {
            assert Double.doubleToRawLongBits(decoded[i]) == Double.doubleToRawLongBits(values[i])
        }
    }

    def "should preserve special floating point values"() {
        given:
        List<Double> specials = [
                0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.MIN_VALUE, Double.MAX_VALUE, 1.0e-300, 1.0e300
        ]

        def writer = new BitWriter()
        def encoder = new GorillaValueEncoder(writer)
        specials.each { encoder.encode(it) }
        encoder.flush()

        when:
        def reader = new BitReader(writer.toByteArray())
        def decoder = new GorillaValueDecoder(reader)
        double[] decoded = decoder.decodeAll(specials.size())

        then:
        for (int i = 0; i < specials.size(); i++) {
            assert Double.doubleToRawLongBits(decoded[i]) == Double.doubleToRawLongBits(specials[i])
        }
    }
}
