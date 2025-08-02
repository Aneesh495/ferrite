package org.ferrite.codec

import spock.lang.Specification
import java.nio.charset.StandardCharsets

class SnappyCodecSpec extends Specification {

    def "should compress and decompress repeated string text losslessly"() {
        given:
        String text = "host=worker-01,region=us-east,env=production;".repeat(100)
        byte[] input = text.getBytes(StandardCharsets.UTF_8)

        when:
        byte[] compressed = SnappyCodec.compress(input)
        byte[] restored = SnappyCodec.decompress(compressed)

        then:
        compressed.length < input.length / 5 // > 5x compression
        new String(restored, StandardCharsets.UTF_8) == text
    }

    def "should handle empty and single byte input"() {
        expect:
        SnappyCodec.compress(new byte[0]).length == 0
        SnappyCodec.decompress(new byte[0]).length == 0

        byte[] single = [0x42] as byte[]
        SnappyCodec.decompress(SnappyCodec.compress(single)) == single
    }
}
