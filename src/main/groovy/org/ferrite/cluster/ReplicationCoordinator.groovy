package org.ferrite.cluster

import groovy.transform.CompileStatic
import org.ferrite.common.MetricPoint
import org.ferrite.common.TimeSeriesKey

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Distributed replication coordinator supporting tunable consistency levels
 * and background read-repair for divergent replicas.
 */
@CompileStatic
class ReplicationCoordinator {

    enum ConsistencyLevel {
        ONE,
        QUORUM,
        ALL
    }

    private final ConsistentHashRing hashRing
    private final int replicationFactor
    private final AtomicLong writesCoordinated = new AtomicLong(0L)
    private final AtomicLong readsCoordinated = new AtomicLong(0L)
    private final AtomicLong readRepairsTriggered = new AtomicLong(0L)

    ReplicationCoordinator(ConsistentHashRing hashRing, int replicationFactor = 3) {
        if (hashRing == null) {
            throw new IllegalArgumentException("ConsistentHashRing cannot be null")
        }
        if (replicationFactor <= 0) {
            throw new IllegalArgumentException("Replication factor must be positive: ${replicationFactor}")
        }
        this.hashRing = hashRing
        this.replicationFactor = replicationFactor
    }

    /**
     * Calculates the required acknowledgement threshold for a given consistency level.
     */
    int requiredAcks(ConsistencyLevel level, int targetNodeCount) {
        int n = Math.min(replicationFactor, targetNodeCount)
        if (n <= 0) return 0

        switch (level) {
            case ConsistencyLevel.ONE:
                return 1
            case ConsistencyLevel.ALL:
                return n
            case ConsistencyLevel.QUORUM:
            default:
                return (int) ((n.intdiv(2) as int) + 1)
        }
    }

    /**
     * Resolves target replica nodes for a given time series key.
     */
    List<NodeId> routeKey(TimeSeriesKey key) {
        if (key == null) return Collections.emptyList()
        hashRing.getPreferenceList(key.seriesId, replicationFactor)
    }

    /**
     * Evaluates a write quorum result across responding replica nodes.
     */
    boolean evaluateWriteQuorum(ConsistencyLevel level, int targetCount, int successfulAcks) {
        writesCoordinated.incrementAndGet()
        int needed = requiredAcks(level, targetCount)
        successfulAcks >= needed
    }

    /**
     * Inspects replica query responses and triggers read-repair if replicas disagree.
     * Returns the authoritative value resolved by latest timestamp or clock order.
     */
    ReplicaReadResult resolveAndRepair(
            ConsistencyLevel level,
            List<ReplicaResponse> responses,
            ReadRepairAction repairAction = null
    ) {
        readsCoordinated.incrementAndGet()
        if (responses == null || responses.isEmpty()) {
            return new ReplicaReadResult(null, false, 0)
        }

        int needed = requiredAcks(level, responses.size())
        if (responses.size() < needed) {
            return new ReplicaReadResult(null, false, responses.size())
        }

        // Detect divergence
        boolean hasDivergence = false
        ReplicaResponse authoritative = responses.get(0)

        for (int i = 1; i < responses.size(); i++) {
            ReplicaResponse curr = responses.get(i)
            if (curr.point == null && authoritative.point != null) {
                hasDivergence = true
            } else if (curr.point != null && authoritative.point == null) {
                hasDivergence = true
                authoritative = curr
            } else if (curr.point != null && authoritative.point != null) {
                // Compare by timestamp then value
                if (curr.point.timestamp > authoritative.point.timestamp) {
                    authoritative = curr
                    hasDivergence = true
                } else if (curr.point.timestamp < authoritative.point.timestamp) {
                    hasDivergence = true
                } else if (Double.compare(curr.point.value, authoritative.point.value) != 0) {
                    hasDivergence = true
                }
            }
        }

        if (hasDivergence) {
            readRepairsTriggered.incrementAndGet()
            if (repairAction != null && authoritative.point != null) {
                for (ReplicaResponse resp : responses) {
                    if (resp != authoritative && (resp.point == null || resp.point.timestamp < authoritative.point.timestamp)) {
                        repairAction.repair(resp.node, authoritative.point)
                    }
                }
            }
        }

        new ReplicaReadResult(authoritative.point, true, responses.size(), hasDivergence)
    }

    long getWritesCoordinated() { writesCoordinated.get() }
    long getReadsCoordinated() { readsCoordinated.get() }
    long getReadRepairsTriggered() { readRepairsTriggered.get() }
    int getReplicationFactor() { replicationFactor }

    static interface ReadRepairAction {
        void repair(NodeId targetNode, MetricPoint authoritativePoint)
    }

    static class ReplicaResponse {
        final NodeId node
        final MetricPoint point

        ReplicaResponse(NodeId node, MetricPoint point) {
            this.node = node
            this.point = point
        }
    }

    static class ReplicaReadResult {
        final MetricPoint point
        final boolean quorumMet
        final int responsesReceived
        final boolean repaired

        ReplicaReadResult(MetricPoint point, boolean quorumMet, int responsesReceived, boolean repaired = false) {
            this.point = point
            this.quorumMet = quorumMet
            this.responsesReceived = responsesReceived
            this.repaired = repaired
        }
    }
}
