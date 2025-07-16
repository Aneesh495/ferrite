package org.ferrite.bitstream

import org.ferrite.common.ByteBuf
import spock.lang.Specification

class BitStreamSpec extends Specification {

    def "should write and read individual bits correctly"() {
        given:
        def writer = new BitWriter()

        when:
        writer.writeBit(1)
        writer.writeBit(0)
        writer.writeBit(1)
        writer.writeBit(1)
        writer.writeBit(0)
        writer.writeBit(0)
        writer.writeBit(1)
        writer.writeBit(0) // 10110010 = 0xB2

        byte[] bytes = writer.toByteArray()
        def reader = new BitReader(bytes)

        then:
        bytes.length == 1
        (bytes[0] & 0xFF) == 0xB2
        reader.readBit() == 1
        reader.readBit() == 0
        reader.readBit() == 1
        reader.readBit() == 1
        reader.readBit() == 0
        reader.readBit() == 0
        reader.readBit() == 1
        reader.readBit() == 0
        !reader.hasMoreBits()
    }

    def "should write and read arbitrary bit widths"() {
        given:
        def writer = new BitWriter()

        when:
        writer.writeBits(0b101L, 3)
        writer.writeBits(0b11110000L, 8)
        writer.writeBits(0x123456789ABCDEFL, 60)
        writer.flush()

        byte[] bytes = writer.toByteArray()
        def reader = new BitReader(bytes)

        then:
        reader.readBits(3) == 0b101L
        reader.readBits(8) == 0b11110000L
        reader.readBits(60) == 0x123456789ABCDEFL
    }

    def "should pad remaining bits on flush"() {
        given:
        def writer = new BitWriter()

        when:
        writer.writeBits(0b11L, 2)
        writer.flush()

        byte[] bytes = writer.toByteArray()

        then:
        bytes.length == 1
        (bytes[0] & 0xFF) == 0b11000000
    }

    def "should encode and decode unsigned varint and varlong"() {
        given:
        def buf = ByteBuf.allocate(64)
        List<Integer> intValues = [0, 1, 63, 127, 128, 255, 16384, 1000000, Integer.MAX_VALUE]
        List<Long> longValues = [0L, 1L, 127L, 128L, 65536L, 1000000000000L, Long.MAX_VALUE]

        when:
        intValues.each { VarInt.writeVarInt(buf, it) }
        longValues.each { VarInt.writeVarLong(buf, it) }

        then:
        intValues.every { VarInt.readVarInt(buf) == it }
        longValues.every { VarInt.readVarLong(buf) == it }
    }

    def "should encode and decode signed values with zigzag"() {
        given:
        def buf = ByteBuf.allocate(64)
        List<Integer> signedInts = [0, -1, 1, -2, 2, -100, 100, Integer.MIN_VALUE, Integer.MAX_VALUE]
        List<Long> signedLongs = [0L, -1L, 1L, -1000000L, 1000000L, Long.MIN_VALUE, Long.MAX_VALUE]

        when:
        signedInts.each { VarInt.writeSignedVarInt(buf, it) }
        signedLongs.each { VarInt.writeSignedVarLong(buf, it) }

        then:
        signedInts.every { VarInt.readSignedVarInt(buf) == it }
        signedLongs.every { VarInt.readSignedVarLong(buf) == it }
    }
}
