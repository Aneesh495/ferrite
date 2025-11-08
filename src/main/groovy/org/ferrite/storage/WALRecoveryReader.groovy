package org.ferrite.storage

import groovy.transform.CompileStatic
import org.ferrite.common.ByteBuf

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.function.Consumer

/**
 * Crash-recovery log reader. Scans segmented WAL directories, validates frame checksums,
 * and automatically truncates incomplete torn writes caused by power failures.
 */
@CompileStatic
class WALRecoveryReader {

    static class RecoveryResult {
        final long entriesRecovered
        final long highestSequenceNumber
        final boolean tornWriteTruncated

        RecoveryResult(long entriesRecovered, long highestSequenceNumber, boolean tornWriteTruncated) {
            this.entriesRecovered = entriesRecovered
            this.highestSequenceNumber = highestSequenceNumber
            this.tornWriteTruncated = tornWriteTruncated
        }
    }

    static RecoveryResult recover(Path walDir, Consumer<WALEntry> entryConsumer) {
        if (!Files.exists(walDir)) {
            return new RecoveryResult(0L, 0L, false)
        }

        List<Path> logFiles = new ArrayList<>()
        Files.list(walDir).withCloseable { stream ->
            stream.filter { p -> p.fileName.toString().startsWith("wal-") && p.fileName.toString().endsWith(".log") }
                    .sorted { p1, p2 -> p1.fileName.toString() <=> p2.fileName.toString() }
                    .forEach { p -> logFiles.add(p) }
        }

        long totalRecovered = 0L
        long maxSeq = 0L
        boolean tornTruncated = false

        for (Path logPath : logFiles) {
            long fileSize = Files.size(logPath)
            if (fileSize == 0L) continue

            FileChannel channel = FileChannel.open(logPath, StandardOpenOption.READ, StandardOpenOption.WRITE)
            channel.withCloseable {
                ByteBuffer fileBuffer = ByteBuffer.allocate((int) fileSize)
                while (fileBuffer.hasRemaining()) {
                    if (channel.read(fileBuffer) < 0) break
                }
                fileBuffer.flip()

                ByteBuf buf = ByteBuf.wrap(fileBuffer)
                long lastValidOffset = 0L

                while (buf.isReadable()) {
                    int entryStart = buf.readerIndex()
                    if (buf.readableBytes() < WALEntry.HEADER_SIZE) {
                        // Torn write at end of segment
                        channel.truncate((long) entryStart)
                        tornTruncated = true
                        break
                    }

                    try {
                        WALEntry entry = WALEntry.readFrom(buf)
                        if (entry == null) {
                            // Incomplete payload
                            channel.truncate((long) entryStart)
                            tornTruncated = true
                            break
                        }

                        entryConsumer.accept(entry)
                        totalRecovered++
                        if (entry.sequenceNumber > maxSeq) {
                            maxSeq = entry.sequenceNumber
                        }
                        lastValidOffset = (long) buf.readerIndex()
                    } catch (CorruptWALException e) {
                        channel.truncate((long) entryStart)
                        tornTruncated = true
                        break
                    }
                }
            }
        }

        new RecoveryResult(totalRecovered, maxSeq, tornTruncated)
    }

    private WALRecoveryReader() {}
}
