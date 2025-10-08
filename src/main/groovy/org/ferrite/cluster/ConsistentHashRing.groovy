package org.ferrite.cluster

import groovy.transform.CompileStatic
import org.ferrite.common.MurmurHash3

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Consistent hash ring with virtual nodes (vnodes) and binary search preference lists.
 * Distributes metric partitions and time series keys uniformly across physical cluster members.
 */
@CompileStatic
class ConsistentHashRing {

    public static final int DEFAULT_VNODES_PER_NODE = 128

    private final int vnodesPerNode
    private final ConcurrentSkipListMap<Long, NodeId> ring = new ConcurrentSkipListMap<Long, NodeId>()
    private final Set<NodeId> physicalNodes = new CopyOnWriteArraySet<NodeId>()

    ConsistentHashRing(int vnodesPerNode = DEFAULT_VNODES_PER_NODE) {
        if (vnodesPerNode <= 0) {
            throw new IllegalArgumentException("Vnodes per node must be positive: ${vnodesPerNode}")
        }
        this.vnodesPerNode = vnodesPerNode
    }

    /**
     * Adds a physical node to the ring, allocating its virtual node tokens.
     */
    synchronized void addNode(NodeId node) {
        if (node == null || physicalNodes.contains(node)) return
        physicalNodes.add(node)

        for (int i = 0; i < vnodesPerNode; i++) {
            String vnodeKey = "${node.id}#vn${i}"
            byte[] bytes = vnodeKey.getBytes(StandardCharsets.UTF_8)
            long token = MurmurHash3.hash64(bytes, 0, bytes.length, 0L)
            ring.put(token, node)
        }
    }

    /**
     * Removes a physical node and purges all of its virtual node tokens from the ring.
     */
    synchronized void removeNode(NodeId node) {
        if (node == null || !physicalNodes.contains(node)) return
        physicalNodes.remove(node)

        for (int i = 0; i < vnodesPerNode; i++) {
            String vnodeKey = "${node.id}#vn${i}"
            byte[] bytes = vnodeKey.getBytes(StandardCharsets.UTF_8)
            long token = MurmurHash3.hash64(bytes, 0, bytes.length, 0L)
            ring.remove(token)
        }
    }

    /**
     * Finds the primary owner node responsible for a given 64-bit token/hash.
     */
    NodeId getNode(long hash) {
        if (ring.isEmpty()) return null
        Long token = ring.ceilingKey(hash)
        if (token == null) {
            token = ring.firstKey()
        }
        ring.get(token)
    }

    /**
     * Computes the replication preference list of distinct physical nodes for a given hash.
     * Traverses the ring clockwise from the hash position, skipping duplicate physical nodes.
     */
    List<NodeId> getPreferenceList(long hash, int replicationFactor) {
        if (ring.isEmpty()) return Collections.emptyList()
        int targetCount = Math.min(replicationFactor, physicalNodes.size())
        List<NodeId> preferenceList = new ArrayList<NodeId>(targetCount)
        Set<NodeId> seen = new HashSet<NodeId>()

        // Search clockwise starting from ceilingKey
        NavigableMap<Long, NodeId> tail = ring.tailMap(hash, true)
        for (NodeId node : tail.values()) {
            if (seen.add(node)) {
                preferenceList.add(node)
                if (preferenceList.size() == targetCount) {
                    return preferenceList
                }
            }
        }

        // Wrap around ring head if needed
        for (NodeId node : ring.values()) {
            if (seen.add(node)) {
                preferenceList.add(node)
                if (preferenceList.size() == targetCount) {
                    return preferenceList
                }
            }
        }

        preferenceList
    }

    int getNodeCount() { physicalNodes.size() }
    int getTokenCount() { ring.size() }
    Set<NodeId> getNodes() { Collections.unmodifiableSet(physicalNodes) }
}
