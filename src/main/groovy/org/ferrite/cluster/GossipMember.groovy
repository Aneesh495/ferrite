package org.ferrite.cluster

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode

/**
 * Cluster membership state descriptor for a node in the SWIM gossip protocol.
 * Tracks incarnation number, failure state machine, and heartbeat timestamps.
 */
@CompileStatic
@EqualsAndHashCode(includes = ["node"])
class GossipMember implements Comparable<GossipMember>, Serializable {
    private static final long serialVersionUID = 1L

    enum Status {
        ALIVE,
        SUSPECT,
        DEAD,
        LEFT
    }

    final NodeId node
    private Status status
    private long incarnation
    private long lastStatusChangeEpochMs
    private long lastHeartbeatEpochMs

    GossipMember(NodeId node, Status status = Status.ALIVE, long incarnation = 0L) {
        if (node == null) {
            throw new IllegalArgumentException("NodeId cannot be null")
        }
        this.node = node
        this.status = status != null ? status : Status.ALIVE
        this.incarnation = incarnation
        long now = System.currentTimeMillis()
        this.lastStatusChangeEpochMs = now
        this.lastHeartbeatEpochMs = now
    }

    synchronized Status getStatus() { status }
    synchronized long getIncarnation() { incarnation }
    synchronized long getLastStatusChangeEpochMs() { lastStatusChangeEpochMs }
    synchronized long getLastHeartbeatEpochMs() { lastHeartbeatEpochMs }

    synchronized void updateStatus(Status newStatus, long newIncarnation) {
        this.status = newStatus
        this.incarnation = newIncarnation
        this.lastStatusChangeEpochMs = System.currentTimeMillis()
    }

    synchronized void recordHeartbeat() {
        this.lastHeartbeatEpochMs = System.currentTimeMillis()
    }

    synchronized void touch() {
        this.lastHeartbeatEpochMs = System.currentTimeMillis()
    }

    @Override
    int compareTo(GossipMember other) {
        if (other == null) return 1
        this.node.compareTo(other.node)
    }

    @Override
    String toString() {
        "${node.id}: ${status} (inc=${incarnation})"
    }
}
