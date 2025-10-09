package org.ferrite.cluster

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import org.ferrite.common.ByteBuf

import java.nio.charset.StandardCharsets

/**
 * Distributed Vector Clock implementation for tracking causal event ordering,
 * concurrent divergence, and conflict resolution across replica nodes.
 */
@CompileStatic
@EqualsAndHashCode
class VectorClock implements Comparable<VectorClock>, Serializable {
    private static final long serialVersionUID = 1L

    enum Ordering {
        BEFORE,
        AFTER,
        EQUAL,
        CONCURRENT
    }

    private final Map<String, Long> versions

    VectorClock() {
        this.versions = Collections.<String, Long>emptyMap()
    }

    VectorClock(Map<String, Long> versions) {
        if (versions == null || versions.isEmpty()) {
            this.versions = Collections.<String, Long>emptyMap()
        } else {
            this.versions = Collections.unmodifiableMap(new TreeMap<String, Long>(versions))
        }
    }

    /**
     * Increments the logical counter for a given node, returning a new immutable VectorClock.
     */
    VectorClock increment(String nodeId) {
        if (nodeId == null || nodeId.trim().isEmpty()) {
            throw new IllegalArgumentException("Node ID cannot be empty")
        }
        Map<String, Long> copy = new TreeMap<String, Long>(this.versions)
        long current = copy.getOrDefault(nodeId, 0L)
        copy.put(nodeId, current + 1L)
        new VectorClock(copy)
    }

    /**
     * Merges two vector clocks by computing the pairwise maximum across all node versions.
     */
    VectorClock merge(VectorClock other) {
        if (other == null || other.versions.isEmpty()) {
            return this
        }
        if (this.versions.isEmpty()) {
            return other
        }

        Map<String, Long> merged = new TreeMap<String, Long>(this.versions)
        for (Map.Entry<String, Long> entry : other.versions.entrySet()) {
            long existing = merged.getOrDefault(entry.key, 0L)
            if (entry.value > existing) {
                merged.put(entry.key, entry.value)
            }
        }
        new VectorClock(merged)
    }

    /**
     * Computes the causal ordering relationship between this clock and another.
     */
    Ordering order(VectorClock other) {
        if (other == null) return Ordering.AFTER

        boolean hasGreater = false
        boolean hasLesser = false

        Set<String> allKeys = new HashSet<String>(this.versions.keySet())
        allKeys.addAll(other.versions.keySet())

        for (String node : allKeys) {
            long v1 = this.versions.getOrDefault(node, 0L)
            long v2 = other.versions.getOrDefault(node, 0L)

            if (v1 > v2) {
                hasGreater = true
            } else if (v1 < v2) {
                hasLesser = true
            }
        }

        if (hasGreater && hasLesser) {
            return Ordering.CONCURRENT
        }
        if (hasGreater) {
            return Ordering.AFTER
        }
        if (hasLesser) {
            return Ordering.BEFORE
        }
        Ordering.EQUAL
    }

    boolean happensBefore(VectorClock other) {
        order(other) == Ordering.BEFORE
    }

    boolean happensAfter(VectorClock other) {
        order(other) == Ordering.AFTER
    }

    boolean isConcurrentWith(VectorClock other) {
        order(other) == Ordering.CONCURRENT
    }

    long getVersion(String nodeId) {
        versions.getOrDefault(nodeId, 0L)
    }

    Map<String, Long> toMap() {
        versions
    }

    int size() {
        versions.size()
    }

    /**
     * Serializes vector clock into binary representation.
     */
    byte[] toByteArray() {
        ByteBuf buf = ByteBuf.allocate(16 + versions.size() * 32)
        buf.writeInt(versions.size())
        for (Map.Entry<String, Long> entry : versions.entrySet()) {
            buf.writeStringUtf8(entry.key)
            buf.writeLong(entry.value)
        }
        buf.toByteArray()
    }

    /**
     * Deserializes vector clock from binary bytes.
     */
    static VectorClock fromByteArray(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new VectorClock()
        }
        ByteBuf buf = ByteBuf.wrap(bytes)
        int size = buf.readInt()
        Map<String, Long> map = new TreeMap<String, Long>()
        for (int i = 0; i < size; i++) {
            String node = buf.readStringUtf8()
            long ver = buf.readLong()
            map.put(node, ver)
        }
        new VectorClock(map)
    }

    @Override
    int compareTo(VectorClock o) {
        Ordering ord = order(o)
        switch (ord) {
            case Ordering.BEFORE: return -1
            case Ordering.AFTER: return 1
            default: return 0
        }
    }

    @Override
    String toString() {
        versions.toString()
    }
}
