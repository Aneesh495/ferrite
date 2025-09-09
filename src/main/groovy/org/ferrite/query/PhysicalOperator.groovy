package org.ferrite.query

import groovy.transform.CompileStatic
import org.ferrite.vector.ColumnarBatch

/**
 * Volcano-style physical execution iterator interface for vectorized query pipelines.
 */
@CompileStatic
interface PhysicalOperator extends AutoCloseable {
    void open()
    ColumnarBatch next()
    @Override
    void close()
}
