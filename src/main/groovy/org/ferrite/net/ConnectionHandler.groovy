package org.ferrite.net

import groovy.transform.CompileStatic
import org.ferrite.common.ByteBuf

import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * State and buffer management for an active non-blocking socket connection.
 */
@CompileStatic
class ConnectionHandler {

    final SocketChannel channel
    private final SelectionKey key
    private final ByteBuf readBuffer = ByteBuf.allocate(8192)
    private final ConcurrentLinkedQueue<ByteBuffer> writeQueue = new ConcurrentLinkedQueue<ByteBuffer>()
    private volatile boolean closed = false

    ConnectionHandler(SocketChannel channel, SelectionKey key) {
        this.channel = channel
        this.key = key
    }

    /**
     * Reads incoming bytes from the channel into the connection read buffer.
     * Returns total bytes read, 0 if no bytes available, or -1 on EOF.
     */
    int read(ByteBuffer scratch) {
        if (closed) return -1
        scratch.clear()
        int bytesRead = channel.read(scratch)
        if (bytesRead > 0) {
            scratch.flip()
            byte[] raw = new byte[bytesRead]
            scratch.get(raw)
            readBuffer.writeBytes(raw)
        }
        bytesRead
    }

    /**
     * Enqueues outbound buffer data for non-blocking socket transmission.
     */
    void send(ByteBuffer data) {
        if (closed || data == null) return
        writeQueue.offer(data)
        if (key != null && key.isValid()) {
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE)
            key.selector().wakeup()
        }
    }

    void send(byte[] bytes) {
        if (bytes != null) {
            send(ByteBuffer.wrap(bytes))
        }
    }

    /**
     * Flushes queued outbound writes to the non-blocking socket channel.
     * Returns true if all queued writes have been drained.
     */
    boolean flush() {
        if (closed) return true

        ByteBuffer buf
        while ((buf = writeQueue.peek()) != null) {
            channel.write(buf)
            if (buf.hasRemaining()) {
                // Socket buffer full; keep OP_WRITE interested
                return false
            }
            writeQueue.poll()
        }

        // Drained all pending writes; clear OP_WRITE
        if (key != null && key.isValid()) {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE)
        }
        true
    }

    void close() {
        if (!closed) {
            closed = true
            try {
                if (key != null) key.cancel()
                channel.close()
            } catch (Exception ignored) {
            }
        }
    }

    ByteBuf getReadBuffer() { readBuffer }
    boolean isClosed() { closed }
}
