package org.ferrite.common

import groovy.transform.CompileStatic

/**
 * Enumeration of supported metric point value data types.
 */
@CompileStatic
enum ValueType {
    DOUBLE((byte) 1),
    LONG((byte) 2),
    STRING((byte) 3),
    BOOLEAN((byte) 4)

    final byte typeId

    ValueType(byte typeId) {
        this.typeId = typeId
    }

    static ValueType fromTypeId(byte id) {
        switch (id) {
            case 1: return DOUBLE
            case 2: return LONG
            case 3: return STRING
            case 4: return BOOLEAN
            default:
                throw new IllegalArgumentException("Unknown ValueType id: ${id}")
        }
    }
}
