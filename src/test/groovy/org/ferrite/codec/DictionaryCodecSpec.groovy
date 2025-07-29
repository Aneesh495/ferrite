package org.ferrite.codec

import org.ferrite.common.ByteBuf
import spock.lang.Specification

class DictionaryCodecSpec extends Specification {

    def "should assign dense sequential IDs to unique strings"() {
        given:
        def dict = new DictionaryCodec()

        when:
        int id1 = dict.encode("cpu.usage")
        int id2 = dict.encode("memory.free")
        int id3 = dict.encode("cpu.usage") // duplicate

        then:
        id1 == 0
        id2 == 1
        id3 == 0
        dict.size() == 2
        dict.decode(0) == "cpu.usage"
        dict.decode(1) == "memory.free"
        dict.decode(99) == null
    }

    def "should serialize and deserialize dictionary to buffer"() {
        given:
        def dict = new DictionaryCodec()
        dict.encode("host")
        dict.encode("datacenter-01")
        dict.encode("production")

        def buf = ByteBuf.allocate(128)

        when:
        dict.writeTo(buf)
        def restored = DictionaryCodec.readFrom(buf)

        then:
        restored.size() == 3
        restored.decode(0) == "host"
        restored.decode(1) == "datacenter-01"
        restored.decode(2) == "production"
    }
}
