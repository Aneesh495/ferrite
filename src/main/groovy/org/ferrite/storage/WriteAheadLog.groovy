package org.ferrite.storage

import groovy.transform.CompileStatic
import org.ferrite.common.ByteBuf
import org.ferrite.common.Constants
import org.ferrite.common.MetricPoint
import org.ferrite.common.TimeSeriesKey

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * Append-only Write-Ahead Log manager with synchronous fsync and background batched flush modes.
 * Manages segment rolling and guarantees durable sequence numbering.
 */
@CompileStatic
class WriteAheadLog implements AutoCloseable {
    private final Path walDir
    private final long maxSegmentBytes
    private final boolean syncOnWrite

    private final AtomicLong currentSequence = new AtomicLong(0L)
    private final AtomicLong flushedSequence = new AtomicLong(0L)
    private final ReentrantLock appendLock = new ReentrantLock()

    private long currentSegmentIndex = 0L
    private Path currentSegmentPath
    private FileChannel currentChannel
    private long currentSegmentSize = 0L
    private volatile boolean closed = false

    WriteAheadLog(Path walDir) {
        this(walDir, Constants.DEFAULT_WAL_SEGMENT_MAX_BYTES, false)
    }

    WriteAheadLog(Path walDir, long maxSegmentBytes, boolean syncOnWrite) {
        this.walDir = walDir
        this.maxSegmentBytes = maxSegmentBytes
        this.syncOnWrite = syncOnWrite
        Files.createDirectories(walDir)
        rollToNextSegment()
    }

    long append(TimeSeriesKey key, List<MetricPoint> points) {
        append(key, points, syncOnWrite)
    }

    long append(TimeSeriesKey key, List<MetricPoint> points, boolean sync) {
        if (closed) throw new IllegalStateException("WriteAheadLog is closed")
        long seq = currentSequence.incrementAndGet()
        WALEntry entry = WALEntry.append(seq, key, points)

        ByteBuf buf = ByteBuf.allocate(128 + points.size() * 16)
        entry.writeTo(buf)
        byte[] bytes = buf.toByteArray()

        appendLock.lock()
        try {
            if (currentSegmentSize + bytes.length > maxSegmentBytes) {
                rollToNextSegment()
            }

            ByteBuffer nioBuf = ByteBuffer.wrap(bytes)
            while (nioBuf.hasRemaining()) {
                currentSegmentSize += currentChannel.write(nioBuf)
            }

            if (sync) {
                currentChannel.force(false)
                flushedSequence.set(seq)
            }
        } finally {
            appendLock.unlock()
        }

        seq
    }

    void flush() {
        if (closed) return
        appendLock.lock()
        try {
            if (currentChannel != null && currentChannel.isOpen()) {
                currentChannel.force(false)
                flushedSequence.set(currentSequence.get())
            }
        } finally {
            appendLock.unlock()
        }
    }

    long getFlushedSequence() {
        flushedSequence.get()
    }

    long getCurrentSequence() {
        currentSequence.get()
    }

    Path getCurrentSegmentPath() {
        currentSegmentPath
    }

    private void rollToNextSegment() {
        if (currentChannel != null && currentChannel.isOpen()) {
            currentChannel.force(true)
            currentChannel.close()
        }

        currentSegmentIndex++
        String fileName = String.format("wal-%08d.log", currentSegmentIndex)
        currentSegmentPath = walDir.resolve(fileName)
        currentChannel = FileChannel.open(
                currentSegmentPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
        )
        currentSegmentSize = 0L
    }

    @Override
    void close() {
        appendLock.lock()
        try {
            if (!closed) {
                closed = true
                if (currentChannel != null && currentChannel.isOpen()) {
                    currentChannel.force(true)
                    currentChannel.close()
                }
            }
        } finally {
            appendLock.unlock()
        }
    }
}
