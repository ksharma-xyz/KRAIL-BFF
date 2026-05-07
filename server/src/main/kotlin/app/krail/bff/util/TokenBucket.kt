package app.krail.bff.util

import java.util.concurrent.atomic.AtomicLong

/**
 * Token-bucket rate limiter.
 *
 * Capacity tokens refill at [refillPerSecond]/sec; each [allow] consumes one.
 * Returns false when the bucket is empty.
 */
class TokenBucket(
    val capacity: Long,
    val refillPerSecond: Long,
) {
    private val tokens = AtomicLong(capacity)
    private val lastRefillMs = AtomicLong(System.currentTimeMillis())

    fun allow(): Boolean {
        refill()
        while (true) {
            val current = tokens.get()
            if (current <= 0) return false
            if (tokens.compareAndSet(current, current - 1)) return true
        }
    }

    /** True iff the bucket is at capacity (no usage in the last capacity/refill seconds). */
    fun isFull(): Boolean {
        refill()
        return tokens.get() >= capacity
    }

    private fun refill() {
        val now = System.currentTimeMillis()
        val last = lastRefillMs.get()
        if (now <= last) return

        val elapsedMs = now - last
        val toAdd = (elapsedMs * refillPerSecond) / 1000
        if (toAdd <= 0) return

        if (lastRefillMs.compareAndSet(last, now)) {
            tokens.updateAndGet { cur ->
                val next = cur + toAdd
                if (next > capacity) capacity else next
            }
        }
    }
}
