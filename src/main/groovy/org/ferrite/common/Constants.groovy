package org.ferrite.common

import groovy.transform.CompileStatic

/**
 * Global constant definitions and default engine parameters for the Ferrite engine.
 */
@CompileStatic
class Constants {
    static final byte[] MAGIC_BYTES = [0x46, 0x45, 0x52, 0x52, 0x49, 0x54, 0x45, 0x31] as byte[] // "FERRITE1"
    static final int WIRE_MAGIC = (int) 0xFE5517E0L
    static final int PROTOCOL_VERSION = 1

    static final int DEFAULT_PORT = 9191
    static final int DEFAULT_HTTP_PORT = 9192
    static final int DEFAULT_GOSSIP_PORT = 9193

    static final int DEFAULT_BLOCK_SIZE_BYTES = 64 * 1024 // 64 KB
    static final int DEFAULT_MAX_FRAME_SIZE = 16 * 1024 * 1024 // 16 MB
    static final int DEFAULT_MEMTABLE_THRESHOLD_BYTES = 32 * 1024 * 1024 // 32 MB
    static final int DEFAULT_WAL_SEGMENT_MAX_BYTES = 64 * 1024 * 1024 // 64 MB
    static final long DEFAULT_WAL_FLUSH_INTERVAL_MS = 100L

    static final int DEFAULT_BLOOM_FILTER_BITS_PER_KEY = 10
    static final int DEFAULT_BLOOM_FILTER_HASH_COUNT = 7

    static final int GORILLA_TIMESTAMP_HEADER_BITS = 14
    static final int GORILLA_SIGNIFICANT_BITS_LENGTH = 6
    static final int GORILLA_LEADING_ZEROS_LENGTH = 5

    private Constants() {}
}
