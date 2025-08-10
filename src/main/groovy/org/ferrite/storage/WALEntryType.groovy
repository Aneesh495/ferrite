package org.ferrite.storage

import groovy.transform.CompileStatic

/**
 * Entry types persisted to the Write-Ahead Log.
 */
@CompileStatic
enum WALEntryType {
    APPEND_POINTS((byte) 1),
    DELETE_SERIES((byte) 2),
    CHECKPOINT((byte) 3)

    final byte id

    WALEntryType(byte id) {
        this.id = id
    }

    static WALEntryType fromId(byte id) {
        switch (id) {
            case 1: return APPEND_POINTS
            case 2: return DELETE_SERIES
            case 3: return CHECKPOINT
            default:
                throw new IllegalArgumentException("Unknown WALEntryType id: ${id}")
        }
    }
}
