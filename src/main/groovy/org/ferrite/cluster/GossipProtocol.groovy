package org.ferrite.cluster

import groovy.transform.CompileStatic

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

/**
 * SWIM-inspired gossip membership protocol and failure detector.
 *
 * Combines direct ping-ack probes, indirect ping requests, suspicion timeouts,
 * and incarnation counters to provide robust decentralized failure detection
 * with bounded false-positive rates under network jitter.
 */
@CompileStatic
class GossipProtocol {

    private final NodeId localNode
    private final ConcurrentHashMap<String, GossipMember> members = new ConcurrentHashMap<String, GossipMember>()
    private final List<Consumer<GossipMember>> membershipListeners = new CopyOnWriteArrayList<Consumer<GossipMember>>()
    private long localIncarnation = 0L
    private final long suspicionTimeoutMs

    GossipProtocol(NodeId localNode, long suspicionTimeoutMs = 5000L) {
        if (localNode == null) {
            throw new IllegalArgumentException("localNode cannot be null")
        }
        this.localNode = localNode
        this.suspicionTimeoutMs = suspicionTimeoutMs
        this.members.put(localNode.id, new GossipMember(localNode, GossipMember.Status.ALIVE, localIncarnation))
    }

    /**
     * Registers a listener for membership state changes.
     */
    void addListener(Consumer<GossipMember> listener) {
        if (listener != null) {
            membershipListeners.add(listener)
        }
    }

    /**
     * Introduces a remote node to the local cluster membership list.
     */
    void addMember(NodeId node) {
        if (node == null || node.id.equals(localNode.id)) return
        GossipMember member = new GossipMember(node, GossipMember.Status.ALIVE, 0L)
        GossipMember existing = members.putIfAbsent(node.id, member)
        if (existing == null) {
            notifyListeners(member)
        }
    }

    /**
     * Applies received gossip membership state updates according to SWIM incarnation rules:
     * - Higher incarnation always overrides lower incarnation.
     * - Same incarnation: DEAD overrides SUSPECT, SUSPECT overrides ALIVE.
     * - If local node is suspected, refutes by incrementing local incarnation and declaring ALIVE.
     */
    synchronized void mergeGossip(List<GossipMember> incoming) {
        if (incoming == null) return

        for (GossipMember remote : incoming) {
            if (remote == null) continue

            // If someone is suspecting or declaring local node dead
            if (remote.node.id.equals(localNode.id)) {
                if (remote.status == GossipMember.Status.SUSPECT || remote.status == GossipMember.Status.DEAD) {
                    if (remote.incarnation >= localIncarnation) {
                        refute(remote.incarnation + 1L)
                    }
                }
                continue
            }

            GossipMember local = members.get(remote.node.id)
            if (local == null) {
                // New member joined
                GossipMember created = new GossipMember(remote.node, remote.status, remote.incarnation)
                members.put(remote.node.id, created)
                notifyListeners(created)
                continue
            }

            // Conflict resolution by incarnation number
            if (remote.incarnation > local.incarnation) {
                local.updateStatus(remote.status, remote.incarnation)
                notifyListeners(local)
            } else if (remote.incarnation == local.incarnation) {
                if (local.status == GossipMember.Status.ALIVE && remote.status == GossipMember.Status.SUSPECT) {
                    local.updateStatus(GossipMember.Status.SUSPECT, remote.incarnation)
                    notifyListeners(local)
                } else if ((local.status == GossipMember.Status.ALIVE || local.status == GossipMember.Status.SUSPECT) &&
                        remote.status == GossipMember.Status.DEAD) {
                    local.updateStatus(GossipMember.Status.DEAD, remote.incarnation)
                    notifyListeners(local)
                }
            }
        }
    }

    /**
     * Refutes a suspicion against the local node by bumping incarnation.
     */
    synchronized void refute(long newIncarnation) {
        this.localIncarnation = newIncarnation
        GossipMember local = members.get(localNode.id)
        if (local != null) {
            local.updateStatus(GossipMember.Status.ALIVE, this.localIncarnation)
            notifyListeners(local)
        }
    }

    /**
     * Marks a member as SUSPECT after an unacknowledged direct probe.
     */
    synchronized void suspect(String nodeId) {
        if (nodeId == null || nodeId.equals(localNode.id)) return
        GossipMember member = members.get(nodeId)
        if (member != null && member.status == GossipMember.Status.ALIVE) {
            member.updateStatus(GossipMember.Status.SUSPECT, member.incarnation)
            notifyListeners(member)
        }
    }

    /**
     * Evaluates active suspicions, transitioning nodes to DEAD once suspicion timeout expires.
     */
    synchronized void checkSuspicions(long nowEpochMs) {
        for (GossipMember member : members.values()) {
            if (member.node.id.equals(localNode.id)) continue

            if (member.status == GossipMember.Status.SUSPECT) {
                long elapsed = nowEpochMs - member.lastStatusChangeEpochMs
                if (elapsed >= suspicionTimeoutMs) {
                    member.updateStatus(GossipMember.Status.DEAD, member.incarnation)
                    notifyListeners(member)
                }
            }
        }
    }

    /**
     * Selects up to k random members to piggyback in outbound gossip payloads.
     */
    List<GossipMember> selectGossipPayload(int maxCount = 5) {
        List<GossipMember> all = new ArrayList<GossipMember>(members.values())
        Collections.shuffle(all)
        all.subList(0, Math.min(maxCount, all.size()))
    }

    /**
     * Returns all currently ALIVE members in the cluster.
     */
    List<NodeId> getAliveNodes() {
        List<NodeId> alive = new ArrayList<NodeId>()
        for (GossipMember member : members.values()) {
            if (member.status == GossipMember.Status.ALIVE) {
                alive.add(member.node)
            }
        }
        alive
    }

    private void notifyListeners(GossipMember member) {
        for (Consumer<GossipMember> listener : membershipListeners) {
            try {
                listener.accept(member)
            } catch (Exception ignored) {
            }
        }
    }

    NodeId getLocalNode() { localNode }
    long getLocalIncarnation() { localIncarnation }
    int getMemberCount() { members.size() }
    GossipMember getMember(String nodeId) { members.get(nodeId) }
}
