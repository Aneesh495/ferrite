package org.ferrite.net

import groovy.transform.CompileStatic
import org.ferrite.common.ByteBuf

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BiConsumer

/**
 * High-performance non-blocking Java NIO Reactor server.
 * Handles high-concurrency client multiplexing with single-thread acceptor
 * and non-blocking event-loop reactor worker threads.
 */
@CompileStatic
class ReactorServer {

    private final String host
    private final int port
    private final BiConsumer<ConnectionHandler, ByteBuf> frameHandler
    private final AtomicBoolean running = new AtomicBoolean(false)

    private ServerSocketChannel serverChannel
    private Selector selector
    private Thread reactorThread
    private final ByteBuffer readScratch = ByteBuffer.allocateDirect(16384)

    ReactorServer(String host, int port, BiConsumer<ConnectionHandler, ByteBuf> frameHandler) {
        this.host = host != null ? host : "0.0.0.0"
        this.port = port
        this.frameHandler = frameHandler
    }

    /**
     * Starts the non-blocking reactor event loop.
     */
    synchronized void start() {
        if (running.get()) return

        selector = Selector.open()
        serverChannel = ServerSocketChannel.open()
        serverChannel.configureBlocking(false)
        serverChannel.bind(new InetSocketAddress(host, port), 1024)
        serverChannel.register(selector, SelectionKey.OP_ACCEPT)

        running.set(true)

        reactorThread = new Thread(new Runnable() {
            @Override
            void run() {
                eventLoop()
            }
        }, "ferrite-reactor-${port}")
        reactorThread.setDaemon(true)
        reactorThread.start()
    }

    private void eventLoop() {
        while (running.get() && selector.isOpen()) {
            try {
                int selected = selector.select(100L)
                if (selected == 0) continue

                Iterator<SelectionKey> it = selector.selectedKeys().iterator()
                while (it.hasNext()) {
                    SelectionKey key = it.next()
                    it.remove()

                    if (!key.isValid()) continue

                    if (key.isAcceptable()) {
                        handleAccept(key)
                    } else {
                        if (key.isReadable()) {
                            handleRead(key)
                        }
                        if (key.isValid() && key.isWritable()) {
                            handleWrite(key)
                        }
                    }
                }
            } catch (Exception e) {
                if (running.get()) {
                    // Log or handle selector exception
                }
            }
        }
    }

    private void handleAccept(SelectionKey key) {
        ServerSocketChannel ssc = (ServerSocketChannel) key.channel()
        SocketChannel client = ssc.accept()
        if (client != null) {
            client.configureBlocking(false)
            SelectionKey clientKey = client.register(selector, SelectionKey.OP_READ)
            ConnectionHandler handler = new ConnectionHandler(client, clientKey)
            clientKey.attach(handler)
        }
    }

    private void handleRead(SelectionKey key) {
        ConnectionHandler handler = (ConnectionHandler) key.attachment()
        if (handler == null) return

        try {
            int read = handler.read(readScratch)
            if (read == -1) {
                handler.close()
                return
            }

            if (read > 0 && frameHandler != null) {
                frameHandler.accept(handler, handler.readBuffer)
            }
        } catch (Exception e) {
            handler.close()
        }
    }

    private void handleWrite(SelectionKey key) {
        ConnectionHandler handler = (ConnectionHandler) key.attachment()
        if (handler != null) {
            try {
                handler.flush()
            } catch (Exception e) {
                handler.close()
            }
        }
    }

    /**
     * Stops the reactor server and shuts down all active channels.
     */
    synchronized void stop() {
        if (!running.compareAndSet(true, false)) return

        try {
            if (selector != null) {
                selector.wakeup()
                for (SelectionKey key : selector.keys()) {
                    try {
                        key.channel().close()
                    } catch (Exception ignored) {
                    }
                }
                selector.close()
            }
            if (serverChannel != null) {
                serverChannel.close()
            }
            if (reactorThread != null) {
                reactorThread.join(2000L)
            }
        } catch (Exception ignored) {
        }
    }

    boolean isRunning() { running.get() }
    int getPort() {
        if (serverChannel != null && serverChannel.isOpen()) {
            try {
                return ((InetSocketAddress) serverChannel.getLocalAddress()).getPort()
            } catch (Exception ignored) {}
        }
        port
    }
}
