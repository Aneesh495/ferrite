package org.ferrite.vector

import groovy.transform.CompileStatic

/**
 * In-memory column-oriented data frame batch.
 * Contains parallel typed column vectors and optional branchless selection vectors.
 */
@CompileStatic
class ColumnarBatch {
    private final String[] columnNames
    private final ColumnVector[] columns
    private final Map<String, Integer> nameToIndex
    private int numRows

    private int[] selectionVector
    private int selectedCount
    private boolean useSelection

    ColumnarBatch(String[] columnNames, ColumnVector[] columns, int numRows) {
        if (columnNames == null || columns == null || columnNames.length != columns.length) {
            throw new IllegalArgumentException("Column names and vectors must be non-null and equal length")
        }
        this.columnNames = columnNames
        this.columns = columns
        this.numRows = numRows
        this.nameToIndex = new HashMap<>(columnNames.length)
        for (int i = 0; i < columnNames.length; i++) {
            nameToIndex.put(columnNames[i], i)
        }
        this.useSelection = false
        this.selectedCount = 0
    }

    static ColumnarBatch of(String[] names, ColumnVector[] vectors, int rowCount) {
        new ColumnarBatch(names, vectors, rowCount)
    }

    int numRows() {
        useSelection ? selectedCount : numRows
    }

    int numColumns() {
        columns.length
    }

    ColumnVector column(int index) {
        columns[index]
    }

    ColumnVector column(String name) {
        Integer idx = nameToIndex.get(name)
        idx != null ? columns[idx] : null
    }

    String columnName(int index) {
        columnNames[index]
    }

    void setSelection(int[] selection, int count) {
        this.selectionVector = selection
        this.selectedCount = count
        this.useSelection = true
    }

    void clearSelection() {
        this.useSelection = false
        this.selectedCount = 0
    }

    boolean isSelectionActive() {
        useSelection
    }

    int getActualRowIndex(int logicalRow) {
        useSelection ? selectionVector[logicalRow] : logicalRow
    }

    DoubleColumnVector doubleColumn(String name) {
        (DoubleColumnVector) column(name)
    }

    TimestampColumnVector timestampColumn(String name) {
        (TimestampColumnVector) column(name)
    }

    StringColumnVector stringColumn(String name) {
        (StringColumnVector) column(name)
    }
}
