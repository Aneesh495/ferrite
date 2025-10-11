package org.ferrite.cluster

import groovy.transform.CompileStatic
import org.ferrite.common.MetricPoint
import org.ferrite.common.MurmurHash3
import org.ferrite.common.TimeRange

import java.nio.ByteBuffer

/**
 * Time-partitioned binary Merkle Tree for background anti-entropy synchronization.
 * Hierarchically computes cryptographic hashes over temporal partitions to locate
 * out-of-sync time ranges between replica nodes in logarithmic time.
 */
@CompileStatic
class MerkleTree {

    public static final int DEFAULT_MAX_DEPTH = 8

    private final TimeRange range
    private final int maxDepth
    private final Node root
    private long sampleCount = 0L

    MerkleTree(TimeRange range, int maxDepth = DEFAULT_MAX_DEPTH) {
        if (range == null) {
            throw new IllegalArgumentException("TimeRange cannot be null")
        }
        if (maxDepth <= 0 || maxDepth > 20) {
            throw new IllegalArgumentException("maxDepth must be in [1, 20]: ${maxDepth}")
        }
        this.range = range
        this.maxDepth = maxDepth
        this.root = buildSubtree(range.fromEpochMs, range.toEpochMs, 0)
    }

    private Node buildSubtree(long start, long end, int depth) {
        if (depth == maxDepth) {
            return new Node(start, end, depth, true)
        }
        long mid = start + ((end - start).intdiv(2L) as long)
        Node left = buildSubtree(start, mid, depth + 1)
        Node right = buildSubtree(mid, end, depth + 1)
        Node parent = new Node(start, end, depth, false)
        parent.left = left
        parent.right = right
        parent
    }

    /**
     * Adds a metric point into the tree, updating its corresponding leaf bucket hash.
     */
    synchronized void add(MetricPoint point) {
        if (point == null) return
        if (point.timestamp < range.fromEpochMs || point.timestamp >= range.toEpochMs) {
            return
        }

        // Hash point components
        byte[] buf = new byte[16]
        ByteBuffer.wrap(buf).putLong(point.timestamp).putDouble(point.value)
        long pointHash = MurmurHash3.hash64(buf, 0, 16, 0L)

        addRecursive(root, point.timestamp, pointHash)
        sampleCount++
    }

    private void addRecursive(Node node, long timestamp, long pointHash) {
        if (node.isLeaf) {
            node.hash = node.hash ^ pointHash
            return
        }

        long mid = node.start + ((node.end - node.start).intdiv(2L) as long)
        if (timestamp < mid) {
            addRecursive(node.left, timestamp, pointHash)
        } else {
            addRecursive(node.right, timestamp, pointHash)
        }

        // Recompute parent hash by combining child hashes
        byte[] pair = new byte[16]
        ByteBuffer.wrap(pair).putLong(node.left.hash).putLong(node.right.hash)
        node.hash = MurmurHash3.hash64(pair, 0, 16, 0L)
    }

    /**
     * Compares this Merkle tree with a replica tree and returns the list of
     * diverging time ranges where data needs to be reconciled.
     */
    List<TimeRange> findDifferences(MerkleTree other) {
        if (other == null) {
            return Collections.singletonList(this.range)
        }
        if (this.root.hash == other.root.hash) {
            // Trees are completely identical
            return Collections.emptyList()
        }

        List<TimeRange> diffs = new ArrayList<TimeRange>()
        diffRecursive(this.root, other.root, diffs)
        diffs
    }

    private void diffRecursive(Node a, Node b, List<TimeRange> diffs) {
        if (a.hash == b.hash) {
            return
        }

        if (a.isLeaf || b.isLeaf) {
            diffs.add(TimeRange.between(Math.min(a.start, b.start), Math.max(a.end, b.end)))
            return
        }

        diffRecursive(a.left, b.left, diffs)
        diffRecursive(a.right, b.right, diffs)
    }

    long getRootHash() {
        root.hash
    }

    TimeRange getRange() {
        range
    }

    int getMaxDepth() {
        maxDepth
    }

    long getSampleCount() {
        sampleCount
    }

    static class Node {
        final long start
        final long end
        final int depth
        final boolean isLeaf
        long hash = 0L
        Node left
        Node right

        Node(long start, long end, int depth, boolean isLeaf) {
            this.start = start
            this.end = end
            this.depth = depth
            this.isLeaf = isLeaf
        }
    }
}
