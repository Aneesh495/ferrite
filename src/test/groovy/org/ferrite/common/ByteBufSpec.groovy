package org.ferrite.common

import spock.lang.Specification

class ByteBufSpec extends Specification {

    def "should write and read primitives with big endian order"() {
        given:
        def buf = ByteBuf.allocate(32)

        when:
        buf.writeByte(0x12)
        buf.writeShort(0x3456)
        buf.writeInt(0x789ABCDE)
        buf.writeLong(0x1122334455667788L)
        buf.writeDouble(3.1415926535)

        then:
        buf.readByte() == (byte) 0x12
        buf.readShort() == (short) 0x3456
        buf.readInt() == 0x789ABCDE
        buf.readLong() == 0x1122334455667788L
        buf.readDouble() == 3.1415926535
        buf.readableBytes() == 0
    }

    def "should auto expand capacity when writing exceeds buffer size"() {
        given:
        def buf = ByteBuf.allocate(4)

        when:
        buf.writeBytes(new byte[100])

        then:
        buf.capacity() >= 100
        buf.readableBytes() == 100
    }

    def "should write and read utf8 strings"() {
        given:
        def buf = ByteBuf.allocate(16)
        String msg = "Distributed telemetry fabric"

        when:
        buf.writeStringUtf8(msg)
        buf.writeStringUtf8(null)

        then:
        buf.readStringUtf8() == msg
        buf.readStringUtf8() == null
    }

    def "should handle mark and reset"() {
        given:
        def buf = ByteBuf.allocate(16)
        buf.writeInt(42)
        buf.markReaderIndex()

        when:
        int val1 = buf.readInt()
        buf.resetReaderIndex()
        int val2 = buf.readInt()

        then:
        val1 == 42
        val2 == 42
    }
}
