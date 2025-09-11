package org.ferrite.query

import groovy.transform.CompileStatic
import org.ferrite.vector.ColumnarBatch

@CompileStatic
interface FilterPredicate {
    int filter(ColumnarBatch batch, int[] outSelection)
}
