package org.ferrite.vector

import groovy.transform.CompileStatic
import org.ferrite.common.ValueType

/**
 * Specialized epoch timestamp vector optimized for temporal interval pruning and binary search.
 */
@CompileStatic
class TimestampColumnVector extends LongColumnVector {
    TimestampColumnVector(int capacity) {
        super(capacity)
    }

    TimestampColumnVector(long[] values) {
        super(values)
    }

    @Override
    ValueType getType() {
        ValueType.LONG
    }
}
