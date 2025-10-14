package org.ferrite.cluster

import org.ferrite.common.MetricPoint
import org.ferrite.common.TimeRange
import org.ferrite.common.TimeSeriesKey
import spock.lang.Specification

class ClusterSpec extends Specification {

    def "ConsistentHashRing should distribute vnodes and compute distinct preference lists"() {
        given:
        ConsistentHashRing ring = new ConsistentHashRing(128)
        NodeId n1 = NodeId.of("node-1", "10.0.0.1", 9090)
        NodeId n2 = NodeId.of("node-2", "10.0.0.2", 9090)
        NodeId n3 = NodeId.of("node-3", "10.0.0.3", 9090)

        when:
        ring.addNode(n1)
        ring.addNode(n2)
        ring.addNode(n3)

        then:
        ring.nodeCount == 3
        ring.tokenCount == 3 * 128

        when: "fetching preference list of size 3 for arbitrary hash"
        List<NodeId> prefs = ring.getPreferenceList(123456789L, 3)

        then: "contains all 3 distinct physical nodes"
        prefs.size() == 3
        prefs.toSet().size() == 3

        when: "removing a node from the ring"
        ring.removeNode(n2)

        then:
        ring.nodeCount == 2
        ring.tokenCount == 2 * 128
        !ring.getNodes().contains(n2)
    }

    def "VectorClock should determine causal ordering and detect concurrency"() {
        given:
        VectorClock c1 = new VectorClock()
        VectorClock c2 = c1.increment("node-A")
        VectorClock c3 = c2.increment("node-A")

        expect: "causal progression"
        c1.happensBefore(c2)
        c2.happensBefore(c3)
        c3.happensAfter(c1)
        c2.order(c2) == VectorClock.Ordering.EQUAL

        when: "concurrent branching occurs"
        VectorClock branch1 = c2.increment("node-A")
        VectorClock branch2 = c2.increment("node-B")

        then: "clocks are concurrent"
        branch1.isConcurrentWith(branch2)
        branch2.isConcurrentWith(branch1)

        when: "merging concurrent clocks"
        VectorClock merged = branch1.merge(branch2)

        then: "merged clock supersedes both branches"
        merged.happensAfter(branch1)
        merged.happensAfter(branch2)
        merged.getVersion("node-A") == 2L
        merged.getVersion("node-B") == 1L
    }

    def "VectorClock should serialize and deserialize losslessly"() {
        given:
        VectorClock orig = new VectorClock()
                .increment("node-alpha")
                .increment("node-beta")
                .increment("node-beta")

        when:
        byte[] bytes = orig.toByteArray()
        VectorClock decoded = VectorClock.fromByteArray(bytes)

        then:
        decoded == orig
        decoded.getVersion("node-alpha") == 1L
        decoded.getVersion("node-beta") == 2L
    }

    def "MerkleTree should detect identical trees and isolate differences"() {
        given:
        TimeRange range = TimeRange.between(0L, 10000L)
        MerkleTree tree1 = new MerkleTree(range, 4)
        MerkleTree tree2 = new MerkleTree(range, 4)

        when: "adding identical samples to both trees"
        10.times { i ->
            MetricPoint pt = new MetricPoint((i * 1000) as long, 42.0d)
            tree1.add(pt)
            tree2.add(pt)
        }

        then: "trees have identical root hash and zero differences"
        tree1.rootHash == tree2.rootHash
        tree1.findDifferences(tree2).isEmpty()

        when: "injecting a diverging point into tree2"
        tree2.add(new MetricPoint(5500L, 99.0d))

        then: "trees differ and findDifferences locates the interval"
        tree1.rootHash != tree2.rootHash
        List<TimeRange> diffs = tree1.findDifferences(tree2)
        !diffs.isEmpty()
        diffs.any { it.contains(5500L) }
    }

    def "GossipProtocol should track members and refute false suspicions"() {
        given:
        NodeId local = NodeId.of("node-local", "127.0.0.1", 8000)
        NodeId peer = NodeId.of("node-peer", "127.0.0.1", 8001)
        GossipProtocol gossip = new GossipProtocol(local, 1000L)

        when: "adding peer"
        gossip.addMember(peer)

        then:
        gossip.memberCount == 2
        gossip.getMember(peer.id).status == GossipMember.Status.ALIVE

        when: "marking peer as suspect"
        gossip.suspect(peer.id)

        then:
        gossip.getMember(peer.id).status == GossipMember.Status.SUSPECT

        when: "checking suspicions after timeout"
        long future = System.currentTimeMillis() + 2000L
        gossip.checkSuspicions(future)

        then:
        gossip.getMember(peer.id).status == GossipMember.Status.DEAD

        when: "peer sends gossip suspecting local node at incarnation 0"
        GossipMember accusation = new GossipMember(local, GossipMember.Status.SUSPECT, 0L)
        gossip.mergeGossip([accusation])

        then: "local node refutes suspicion and bumps its incarnation to 1"
        gossip.localIncarnation == 1L
        gossip.getMember(local.id).status == GossipMember.Status.ALIVE
    }

    def "ReplicationCoordinator should coordinate quorums and trigger read repair"() {
        given:
        ConsistentHashRing ring = new ConsistentHashRing()
        NodeId n1 = NodeId.of("n1", "127.0.0.1", 7001)
        NodeId n2 = NodeId.of("n2", "127.0.0.1", 7002)
        NodeId n3 = NodeId.of("n3", "127.0.0.1", 7003)
        ring.addNode(n1)
        ring.addNode(n2)
        ring.addNode(n3)

        ReplicationCoordinator coord = new ReplicationCoordinator(ring, 3)

        expect: "quorum ack arithmetic"
        coord.requiredAcks(ReplicationCoordinator.ConsistencyLevel.ONE, 3) == 1
        coord.requiredAcks(ReplicationCoordinator.ConsistencyLevel.QUORUM, 3) == 2
        coord.requiredAcks(ReplicationCoordinator.ConsistencyLevel.ALL, 3) == 3

        and: "write quorum evaluation"
        coord.evaluateWriteQuorum(ReplicationCoordinator.ConsistencyLevel.QUORUM, 3, 2) == true
        coord.evaluateWriteQuorum(ReplicationCoordinator.ConsistencyLevel.QUORUM, 3, 1) == false

        when: "reading divergent responses where one replica is stale"
        MetricPoint freshPoint = new MetricPoint(2000L, 88.0d)
        MetricPoint stalePoint = new MetricPoint(1000L, 50.0d)

        List<ReplicationCoordinator.ReplicaResponse> responses = [
                new ReplicationCoordinator.ReplicaResponse(n1, freshPoint),
                new ReplicationCoordinator.ReplicaResponse(n2, stalePoint)
        ]

        NodeId repairedTarget = null
        MetricPoint repairedWith = null

        def result = coord.resolveAndRepair(
                ReplicationCoordinator.ConsistencyLevel.QUORUM,
                responses,
                new ReplicationCoordinator.ReadRepairAction() {
                    @Override
                    void repair(NodeId target, MetricPoint auth) {
                        repairedTarget = target
                        repairedWith = auth
                    }
                }
        )

        then: "authoritative point is the fresh point and read repair is fired"
        result.quorumMet == true
        result.point == freshPoint
        result.repaired == true
        repairedTarget == n2
        repairedWith == freshPoint
        coord.readRepairsTriggered == 1L
    }
}
