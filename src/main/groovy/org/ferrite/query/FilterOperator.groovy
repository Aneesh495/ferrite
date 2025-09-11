package org.ferrite.query

import groovy.transform.CompileStatic
import org.ferrite.vector.ColumnarBatch

/**
 * Vectorized filter operator.
 * Evaluates filter predicates using branchless selection vectors without row copying.
 */
@CompileStatic
class FilterOperator implements PhysicalOperator {
    private final PhysicalOperator child
    private final FilterPredicate predicate

    FilterOperator(PhysicalOperator child, FilterPredicate predicate) {
        if (child == null || predicate == null) {
            throw new IllegalArgumentException("Child and predicate must be non-null")
        }
        this.child = child
        this.predicate = predicate
    }

    @Override
    void open() {
        child.open()
    }

    @Override
    ColumnarBatch next() {
        while (true) {
            ColumnarBatch batch = child.next()
            if (batch == null) return null

            int n = batch.numRows()
            if (n == 0) continue

            int[] selection = new int[n]
            int matched = predicate.filter(batch, selection)

            if (matched > 0) {
                batch.setSelection(selection, matched)
                return batch
            }
        }
    }

    @Override
    void close() {
        child.close()
    }
}
