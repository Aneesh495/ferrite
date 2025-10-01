package org.ferrite.analytics

import groovy.transform.CompileStatic
import org.ferrite.common.MurmurHash3

import java.nio.charset.StandardCharsets

/**
 * Count-Min Sketch frequency table with conservative update heuristic.
 * Tracks heavy-hitter metric keys and event frequencies in streaming telemetry.
 */
@CompileStatic
class CountMinSketch implements Serializable {
    private static final long serialVersionUID = 1L

    private final int depth
    private final int width
    private final long[][] table
    private long totalCount

    CountMinSketch(double eps, double confidence) {
        if (eps <= 0.0 || eps >= 1.0 || confidence <= 0.0 || confidence >= 1.0) {
            throw new IllegalArgumentException("Invalid eps/confidence parameters")
        }
        this.width = (int) Math.ceil(Math.E / eps)
        this.depth = (int) Math.ceil(Math.log(1.0 / (1.0 - confidence)))
        this.table = new long[depth][width]
        this.totalCount = 0L
    }

    CountMinSketch(int depth, int width) {
        this.depth = depth
        this.width = width
        this.table = new long[depth][width]
        this.totalCount = 0L
    }

    void add(String item) {
        update(item, 1L)
    }

    void add(String item, long count) {
        update(item, count)
    }

    void update(String item) {
        update(item, 1L)
    }

    void update(String item, long count) {
        if (item == null || count <= 0L) return
        byte[] bytes = item.getBytes(StandardCharsets.UTF_8)
        long[] hashes = MurmurHash3.hash128(bytes, 0, bytes.length, 0L)
        long h1 = hashes[0]
        long h2 = hashes[1]

        // Conservative update heuristic: determine current minimum value
        long minVal = Long.MAX_VALUE
        int[] positions = new int[depth]
        long combined = h1

        for (int i = 0; i < depth; i++) {
            int col = (int) ((combined & Long.MAX_VALUE) % (long) width)
            positions[i] = col
            if (table[i][col] < minVal) {
                minVal = table[i][col]
            }
            combined += h2
        }

        // Only increment counters that match minVal
        long targetVal = minVal + count
        for (int i = 0; i < depth; i++) {
            int col = positions[i]
            if (table[i][col] < targetVal) {
                table[i][col] = targetVal
            }
        }
        totalCount += count
    }

    long estimate(String item) {
        if (item == null) return 0L
        byte[] bytes = item.getBytes(StandardCharsets.UTF_8)
        long[] hashes = MurmurHash3.hash128(bytes, 0, bytes.length, 0L)
        long h1 = hashes[0]
        long h2 = hashes[1]

        long min = Long.MAX_VALUE
        long combined = h1

        for (int i = 0; i < depth; i++) {
            int col = (int) ((combined & Long.MAX_VALUE) % (long) width)
            long val = table[i][col]
            if (val < min) {
                min = val
            }
            combined += h2
        }
        min == Long.MAX_VALUE ? 0L : min
    }

    long getTotalCount() {
        totalCount
    }
}
