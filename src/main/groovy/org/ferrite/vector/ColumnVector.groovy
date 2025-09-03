package org.ferrite.vector

import groovy.transform.CompileStatic
import org.ferrite.common.ValueType

/**
 * Common columnar vector interface representing a contiguous primitive column slice.
 */
@CompileStatic
interface ColumnVector {
    int size()
    ValueType getType()
    boolean isNullAt(int row)
    void setNullAt(int row)
}
