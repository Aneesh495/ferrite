package org.ferrite.common

import groovy.transform.CompileStatic

/**
 * Represents an individual timestamped measurement sample.
 * Optimized for high-throughput ingestion and cache locality.
 */
@CompileStatic
class MetricPoint implements Comparable<MetricPoint>, Serializable {
    private static final long serialVersionUID = 1L

    final long timestamp
    final double value
    final ValueType valueType
    final Map<String, Object> auxiliaryFields

    MetricPoint(long timestamp, double value) {
        this(timestamp, value, ValueType.DOUBLE, Collections.<String, Object>emptyMap())
    }

    MetricPoint(long timestamp, long longValue) {
        this(timestamp, (double) longValue, ValueType.LONG, Collections.<String, Object>emptyMap())
    }

    MetricPoint(long timestamp, double value, ValueType valueType, Map<String, Object> auxiliaryFields) {
        this.timestamp = timestamp
        this.value = value
        this.valueType = valueType != null ? valueType : ValueType.DOUBLE
        Map<String, Object> copy = new HashMap<String, Object>()
        if (auxiliaryFields != null) {
            copy.putAll(auxiliaryFields)
        }
        this.auxiliaryFields = Collections.unmodifiableMap(copy)
    }

    static MetricPoint of(long timestamp, double value) {
        new MetricPoint(timestamp, value)
    }

    static MetricPoint of(long timestamp, long value) {
        new MetricPoint(timestamp, value)
    }

    static MetricPoint of(long timestamp, double value, Map<String, Object> fields) {
        new MetricPoint(timestamp, value, ValueType.DOUBLE, fields)
    }

    long getLongValue() {
        (long) value
    }

    boolean isAuxiliaryPresent() {
        !auxiliaryFields.isEmpty()
    }

    Object getField(String fieldName) {
        auxiliaryFields.get(fieldName)
    }

    @Override
    int compareTo(MetricPoint o) {
        int cmp = Long.compare(this.timestamp, o.timestamp)
        if (cmp != 0) return cmp
        Double.compare(this.value, o.value)
    }

    @Override
    boolean equals(Object o) {
        if (this.is(o)) return true
        if (!(o instanceof MetricPoint)) return false
        MetricPoint that = (MetricPoint) o
        if (timestamp != that.timestamp) return false
        if (Double.compare(that.value, value) != 0) return false
        if (valueType != that.valueType) return false
        auxiliaryFields == that.auxiliaryFields
    }

    @Override
    int hashCode() {
        int result = (int) (timestamp ^ (timestamp >>> 32))
        long temp = Double.doubleToLongBits(value)
        result = 31 * result + (int) (temp ^ (temp >>> 32))
        result = 31 * result + valueType.hashCode()
        result = 31 * result + auxiliaryFields.hashCode()
        result
    }

    @Override
    String toString() {
        "MetricPoint(ts=${timestamp}, val=${value}, type=${valueType}" +
                (auxiliaryFields.isEmpty() ? "" : ", aux=${auxiliaryFields}") + ")"
    }
}
