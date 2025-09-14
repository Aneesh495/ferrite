package org.ferrite.query

import groovy.transform.CompileStatic
import org.ferrite.vector.ColumnVector
import org.ferrite.vector.ColumnarBatch
import org.ferrite.vector.DoubleColumnVector

/**
 * Columnar projection operator.
 * Restricts downstream operator visibility to specified projection columns and applies scalar multipliers.
 */
@CompileStatic
class ProjectOperator implements PhysicalOperator {
    private final PhysicalOperator child
    private final String[] projectColumns
    private final double scaleMultiplier
    private final double offsetAdd

    ProjectOperator(PhysicalOperator child, String[] projectColumns) {
        this(child, projectColumns, 1.0, 0.0)
    }

    ProjectOperator(PhysicalOperator child, String[] projectColumns, double scaleMultiplier, double offsetAdd) {
        if (child == null || projectColumns == null || projectColumns.length == 0) {
            throw new IllegalArgumentException("Child and projection columns must be valid")
        }
        this.child = child
        this.projectColumns = projectColumns
        this.scaleMultiplier = scaleMultiplier
        this.offsetAdd = offsetAdd
    }

    @Override
    void open() {
        child.open()
    }

    @Override
    ColumnarBatch next() {
        ColumnarBatch batch = child.next()
        if (batch == null) return null

        int numCols = projectColumns.length
        ColumnVector[] newCols = new ColumnVector[numCols]
        int n = batch.numRows()

        for (int i = 0; i < numCols; i++) {
            String colName = projectColumns[i]
            ColumnVector original = batch.column(colName)
            if (original == null) {
                throw new IllegalArgumentException("Column not found in batch: ${colName}")
            }

            if (scaleMultiplier != 1.0 || offsetAdd != 0.0) {
                if (original instanceof DoubleColumnVector) {
                    DoubleColumnVector dCol = (DoubleColumnVector) original
                    DoubleColumnVector projected = new DoubleColumnVector(n)
                    for (int r = 0; r < n; r++) {
                        int actualRow = batch.getActualRowIndex(r)
                        if (dCol.isNullAt(actualRow)) {
                            projected.append(0.0)
                            projected.setNullAt(r)
                        } else {
                            projected.append(dCol.getDouble(actualRow) * scaleMultiplier + offsetAdd)
                        }
                    }
                    newCols[i] = projected
                } else {
                    newCols[i] = original
                }
            } else {
                newCols[i] = original
            }
        }

        ColumnarBatch result = ColumnarBatch.of(projectColumns, newCols, n)
        if (batch.isSelectionActive() && (scaleMultiplier == 1.0 && offsetAdd == 0.0)) {
            // Forward selection vector if rows weren't re-indexed
            int[] sel = new int[n]
            for (int r = 0; r < n; r++) {
                sel[r] = batch.getActualRowIndex(r)
            }
            result.setSelection(sel, n)
        }
        result
    }

    @Override
    void close() {
        child.close()
    }
}
