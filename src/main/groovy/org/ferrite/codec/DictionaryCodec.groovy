package org.ferrite.codec

import groovy.transform.CompileStatic
import org.ferrite.bitstream.VarInt
import org.ferrite.common.ByteBuf

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two-way bidirectional string dictionary encoder.
 * Deduplicates high-cardinality tag dimensions and metric names into dense 32-bit symbols.
 */
@CompileStatic
class DictionaryCodec {
    private final Map<String, Integer> stringToId
    private final List<String> idToString
    private final AtomicInteger nextId

    DictionaryCodec() {
        this.stringToId = new ConcurrentHashMap<>()
        this.idToString = Collections.synchronizedList(new ArrayList<String>())
        this.nextId = new AtomicInteger(0)
    }

    int encode(String text) {
        if (text == null) return -1
        Integer existing = stringToId.get(text)
        if (existing != null) {
            return existing.intValue()
        }

        synchronized (this) {
            existing = stringToId.get(text)
            if (existing != null) {
                return existing.intValue()
            }
            int id = nextId.getAndIncrement()
            stringToId.put(text, id)
            idToString.add(text)
            return id
        }
    }

    String decode(int id) {
        if (id < 0 || id >= idToString.size()) {
            return null
        }
        idToString.get(id)
    }

    int size() {
        idToString.size()
    }

    boolean contains(String text) {
        text != null && stringToId.containsKey(text)
    }

    void writeTo(ByteBuf buf) {
        int count = size()
        VarInt.writeVarInt(buf, count)
        for (int i = 0; i < count; i++) {
            buf.writeStringUtf8(idToString.get(i))
        }
    }

    static DictionaryCodec readFrom(ByteBuf buf) {
        DictionaryCodec codec = new DictionaryCodec()
        int count = VarInt.readVarInt(buf)
        for (int i = 0; i < count; i++) {
            String str = buf.readStringUtf8()
            codec.encode(str)
        }
        codec
    }
}
