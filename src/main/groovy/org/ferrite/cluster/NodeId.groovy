package org.ferrite.cluster

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode

/**
 * Immutable identifier and network address for a cluster node.
 */
@CompileStatic
@EqualsAndHashCode
class NodeId implements Comparable<NodeId>, Serializable {
    private static final long serialVersionUID = 1L

    final String id
    final String host
    final int port
    final String datacenter
    final String rack

    NodeId(String id, String host, int port, String datacenter = "default", String rack = "default") {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("Node ID cannot be null or empty")
        }
        this.id = id.trim()
        this.host = host != null ? host.trim() : "127.0.0.1"
        this.port = port
        this.datacenter = datacenter != null ? datacenter.trim() : "default"
        this.rack = rack != null ? rack.trim() : "default"
    }

    static NodeId of(String id, String host, int port) {
        new NodeId(id, host, port)
    }

    static NodeId of(String id, String host, int port, String datacenter, String rack) {
        new NodeId(id, host, port, datacenter, rack)
    }

    @Override
    int compareTo(NodeId other) {
        if (other == null) return 1
        this.id.compareTo(other.id)
    }

    @Override
    String toString() {
        "${id}@${host}:${port}"
    }
}
