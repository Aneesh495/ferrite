package org.ferrite.codec

import groovy.transform.CompileStatic

/**
 * Supported compression and encoding algorithms in Ferrite.
 */
@CompileStatic
enum CodecType {
    RAW((byte) 0),
    GORILLA((byte) 1),
    SNAPPY((byte) 2),
    DICTIONARY((byte) 3)

    final byte id

    CodecType(byte id) {
        this.id = id
    }

    static CodecType fromId(byte id) {
        switch (id) {
            case 0: return RAW
            case 1: return GORILLA
            case 2: return SNAPPY
            case 3: return DICTIONARY
            default:
                throw new IllegalArgumentException("Unknown CodecType id: ${id}")
        }
    }
}
