package org.ferrite.common

import spock.lang.Specification
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class CRC32CSpec extends Specification {

    def "should compute standard known CRC32C vectors"() {
        expect:
        // Known Castagnoli vectors
        CRC32C.compute("123456789".getBytes(StandardCharsets.US_ASCII)) == (int) 0xe3069283L
        CRC32C.compute("".getBytes(StandardCharsets.US_ASCII)) == 0
    }

    def "should match software table fallback exactly"() {
        given:
        byte[] data = "High-throughput distributed storage fabric engine".getBytes(StandardCharsets.UTF_8)

        when:
        int intrinsicCrc = CRC32C.compute(data)
        int softwareCrc = CRC32C.computeSoftware(data, 0, data.length)

        then:
        intrinsicCrc == softwareCrc
    }

    def "should compute byte buffer slice crc"() {
        given:
        byte[] raw = "prefix__payload_bytes__suffix".getBytes(StandardCharsets.UTF_8)
        ByteBuffer buf = ByteBuffer.wrap(raw)

        when:
        int crc = CRC32C.compute(buf, 8, 13) // "payload_bytes"
        int expected = CRC32C.compute("payload_bytes".getBytes(StandardCharsets.UTF_8))

        then:
        crc == expected
    }

    def "should mask and unmask crc without loss"() {
        given:
        int crc = (int) 0xe3069283L

        when:
        int masked = CRC32C.mask(crc)
        int unmasked = CRC32C.unmask(masked)

        then:
        masked != crc
        unmasked == crc
    }
}
