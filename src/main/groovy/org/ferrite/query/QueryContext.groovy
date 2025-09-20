package org.ferrite.query

import groovy.transform.CompileStatic
import org.ferrite.storage.StorageEngine

/**
 * Execution context carrying environmental parameters, memory bounds, and storage hooks.
 */
@CompileStatic
class QueryContext {
    final StorageEngine storageEngine
    final long timeoutMs
    final int maxResultRows

    QueryContext(StorageEngine storageEngine) {
        this(storageEngine, 30000L, 100000)
    }

    QueryContext(StorageEngine storageEngine, long timeoutMs, int maxResultRows) {
        this.storageEngine = storageEngine
        this.timeoutMs = timeoutMs
        this.maxResultRows = maxResultRows
    }
}
