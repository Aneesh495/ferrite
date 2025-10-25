package org.ferrite.net

import groovy.transform.CompileStatic

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Off-heap direct memory byte buffer pool with slab recycling.
 * Eliminates GC allocation churn in the network I/O path.
 */
@CompileStatic
class BufferPool {

    private final int slabSize
    private final int maxPoolSize
    private final boolean direct
    private final ConcurrentLinkedQueue<ByteBuffer> pool = new ConcurrentLinkedQueue<ByteBuffer>()
    private final AtomicInteger pooledCount = new AtomicInteger(0)
    private final AtomicLong leases = new AtomicLong(0L)
    private final AtomicLong releases = new AtomicLong(0L)
    private final AtomicLong allocations = new AtomicLong(0L)

    BufferPool(int slabSize = 16384, int maxPoolSize = 512, boolean direct = true) {
        if (slabSize <= 0) {
            throw new IllegalArgumentException("Slab size must be positive: ${slabSize}")
        }
        if (maxPoolSize <= 0) {
            throw new IllegalArgumentException("Max pool size must be positive: ${maxPoolSize}")
        }
        this.slabSize = slabSize
        this.maxPoolSize = maxPoolSize
        this.direct = direct
    }

    /**
     * Borrows a recycled buffer from the pool, or allocates a new slab if empty.
     */
    ByteBuffer acquire() {
        leases.incrementAndGet()
        ByteBuffer buf = pool.poll()
        if (buf != null) {
            pooledCount.decrementAndGet()
            buf.clear()
            return buf
        }

        allocations.incrementAndGet()
        direct ? ByteBuffer.allocateDirect(slabSize) : ByteBuffer.allocate(slabSize)
    }

    /**
     * Returns a buffer to the recycling pool if capacity permits.
     */
    void release(ByteBuffer buf) {
        if (buf == null || buf.capacity() != slabSize) return
        releases.incrementAndGet()

        if (pooledCount.get() < maxPoolSize) {
            buf.clear()
            pool.offer(buf)
            pooledCount.incrementAndGet()
        }
    }

    int getSlabSize() { slabSize }
    int getMaxPoolSize() { maxPoolSize }
    int getAvailableCount() { pooledCount.get() }
    long getLeases() { leases.get() }
    long getReleases() { releases.get() }
    long getAllocations() { allocations.get() }
}
