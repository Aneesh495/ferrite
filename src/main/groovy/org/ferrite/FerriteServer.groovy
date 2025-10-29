package org.ferrite

import groovy.transform.CompileStatic
import org.ferrite.cluster.ConsistentHashRing
import org.ferrite.cluster.GossipProtocol
import org.ferrite.cluster.NodeId
import org.ferrite.common.ByteBuf
import org.ferrite.net.BufferPool
import org.ferrite.net.ConnectionHandler
import org.ferrite.net.HttpEngine
import org.ferrite.net.ReactorServer
import org.ferrite.protocol.BinaryFrame
import org.ferrite.protocol.BinaryFrameCodec
import org.ferrite.query.QueryEngine
import org.ferrite.storage.StorageEngine

import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ferrite Telemetry Engine standalone daemon and cluster coordinator entrypoint.
 * Bootstraps the storage engine, vector query pipeline, gossip consensus,
 * binary NIO reactor, and HTTP REST services with graceful shutdown management.
 */
@CompileStatic
class FerriteServer {

    private final Path dataDir
    private final int httpPort
    private final int reactorPort
    private final NodeId localNode

    private StorageEngine storageEngine
    private QueryEngine queryEngine
    private ConsistentHashRing hashRing
    private GossipProtocol gossip
    private ReactorServer reactorServer
    private HttpEngine httpEngine
    private BufferPool bufferPool

    private final AtomicBoolean running = new AtomicBoolean(false)
    private Thread shutdownHook

    FerriteServer(
            Path dataDir = Paths.get("data"),
            int httpPort = 8086,
            int reactorPort = 9091,
            String nodeId = "ferrite-node-1",
            String host = "127.0.0.1"
    ) {
        this.dataDir = dataDir
        this.httpPort = httpPort
        this.reactorPort = reactorPort
        this.localNode = NodeId.of(nodeId, host, reactorPort)
    }

    /**
     * Initializes and starts all engine subsystems.
     */
    synchronized void start() {
        if (!running.compareAndSet(false, true)) return

        printBanner()

        // 1. Initialize off-heap buffer pool
        bufferPool = new BufferPool(16384, 512, true)

        // 2. Initialize storage engine and recover WAL
        println "[ferrite] Initializing storage engine at: ${dataDir.toAbsolutePath()}"
        storageEngine = new StorageEngine(dataDir)

        // 3. Initialize query execution engine
        queryEngine = new QueryEngine(storageEngine)

        // 4. Initialize cluster topology and gossip protocol
        println "[ferrite] Joining cluster topology as node: ${localNode}"
        hashRing = new ConsistentHashRing(128)
        hashRing.addNode(localNode)
        gossip = new GossipProtocol(localNode, 5000L)

        // 5. Initialize binary NIO reactor server
        println "[ferrite] Starting binary reactor on port: ${reactorPort}"
        reactorServer = new ReactorServer(localNode.host, reactorPort, { ConnectionHandler conn, ByteBuf buf ->
            handleBinaryMessage(conn, buf)
        })
        reactorServer.start()

        // 6. Initialize HTTP REST and ingestion engine
        println "[ferrite] Starting HTTP API on port: ${httpPort}"
        httpEngine = new HttpEngine(localNode.host, httpPort, storageEngine, queryEngine)
        httpEngine.start()

        // 7. Register JVM shutdown hook
        shutdownHook = new Thread({
            stop()
        }, "ferrite-shutdown-hook")
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        println "[ferrite] Daemon online and ready to accept telemetry streams."
    }

    private void handleBinaryMessage(ConnectionHandler conn, ByteBuf buf) {
        try {
            BinaryFrame frame
            while ((frame = BinaryFrameCodec.decode(buf)) != null) {
                switch (frame.type) {
                    case BinaryFrame.Type.PING:
                        conn.send(BinaryFrameCodec.encode(BinaryFrame.pong(frame.streamId)))
                        break
                    case BinaryFrame.Type.PONG:
                        // Heartbeat reply acknowledged
                        break
                    default:
                        // Generic response
                        conn.send(BinaryFrameCodec.encode(new BinaryFrame(BinaryFrame.Type.INGEST_ACK, frame.streamId)))
                        break
                }
            }
        } catch (Exception e) {
            conn.send(BinaryFrameCodec.encode(BinaryFrame.error(0L, e.getMessage())))
        }
    }

    /**
     * Gracefully stops all engine subsystems, flushes WAL, and closes segments.
     */
    synchronized void stop() {
        if (!running.compareAndSet(true, false)) return

        println "[ferrite] Initiating graceful shutdown..."

        if (httpEngine != null) {
            try { httpEngine.stop() } catch (Exception ignored) {}
        }
        if (reactorServer != null) {
            try { reactorServer.stop() } catch (Exception ignored) {}
        }
        if (storageEngine != null) {
            try { storageEngine.close() } catch (Exception ignored) {}
        }

        println "[ferrite] Shutdown complete."
    }

    private void printBanner() {
        println """
========================================================================
   ______             _ _
  |  ____|           (_) |
  | |__ ___ _ __ _ __ _| |_ ___
  |  __/ _ \\ '__| '__| | __/ _ \\
  | | |  __/ |  | |  | | ||  __/
  |_|  \\___|_|  |_|  |_|\\__\\___|

  High-Performance Time-Series Engine & Columnar Analytics Runtime
  Version 1.0.0
========================================================================
"""
    }

    boolean isRunning() { running.get() }
    StorageEngine getStorageEngine() { storageEngine }
    QueryEngine getQueryEngine() { queryEngine }
    ConsistentHashRing getHashRing() { hashRing }
    GossipProtocol getGossip() { gossip }
    HttpEngine getHttpEngine() { httpEngine }
    ReactorServer getReactorServer() { reactorServer }

    static void main(String[] args) {
        Path data = Paths.get("data")
        int http = 8086
        int reactor = 9091
        String node = "ferrite-node-1"
        String host = "127.0.0.1"

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--data-dir") && i + 1 < args.length) {
                data = Paths.get(args[++i])
            } else if (args[i].equals("--http-port") && i + 1 < args.length) {
                http = Integer.parseInt(args[++i])
            } else if (args[i].equals("--reactor-port") && i + 1 < args.length) {
                reactor = Integer.parseInt(args[++i])
            } else if (args[i].equals("--node-id") && i + 1 < args.length) {
                node = args[++i]
            } else if (args[i].equals("--host") && i + 1 < args.length) {
                host = args[++i]
            }
        }

        FerriteServer server = new FerriteServer(data, http, reactor, node, host)
        server.start()
    }
}
