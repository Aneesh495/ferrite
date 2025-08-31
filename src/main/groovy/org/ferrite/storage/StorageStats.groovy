package org.ferrite.storage

import groovy.transform.CompileStatic

@CompileStatic
class StorageStats {
    final long totalPointsWritten
    final long activeMemTablePoints
    final long activeMemTableBytes
    final int activeSegmentsCount
    final long totalSegmentSizeBytes
    final long totalFlushes
    final long totalCompactions

    StorageStats(long totalPointsWritten, long activeMemTablePoints, long activeMemTableBytes,
                 int activeSegmentsCount, long totalSegmentSizeBytes, long totalFlushes, long totalCompactions) {
        this.totalPointsWritten = totalPointsWritten
        this.activeMemTablePoints = activeMemTablePoints
        this.activeMemTableBytes = activeMemTableBytes
        this.activeSegmentsCount = activeSegmentsCount
        this.totalSegmentSizeBytes = totalSegmentSizeBytes
        this.totalFlushes = totalFlushes
        this.totalCompactions = totalCompactions
    }

    @Override
    String toString() {
        "StorageStats(pointsWritten=${totalPointsWritten}, memPoints=${activeMemTablePoints}, " +
                "memBytes=${activeMemTableBytes}, segments=${activeSegmentsCount}, segmentBytes=${totalSegmentSizeBytes}, " +
                "flushes=${totalFlushes}, compactions=${totalCompactions})"
    }
}
