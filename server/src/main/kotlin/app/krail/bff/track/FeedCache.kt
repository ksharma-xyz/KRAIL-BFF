package app.krail.bff.track

import com.google.transit.realtime.FeedMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory TTL cache of decoded GTFS-Realtime feeds with single-flight
 * loading: regardless of how many users are tracking, each feed is fetched
 * from NSW at most once per TTL window, and concurrent requests during a
 * refresh share one upstream call instead of stampeding.
 *
 * This is the component that keeps tracking inside the NSW rate limit
 * (5 req/s) and daily budget no matter the user count — worst-case upstream
 * cost is feeds × (86400 / ttl) per day, independent of traffic.
 *
 * Failure policy: a failed load propagates to the caller (the leg resolves
 * to UPSTREAM_UNAVAILABLE) but a stale entry, when present and younger than
 * [staleGraceMillis], is served instead — brief NSW blips shouldn't blank
 * the tracking screen.
 */
class FeedCache(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val feed: FeedMessage, val fetchedAtMillis: Long)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Serve-stale window after a refresh failure. */
    private val staleGraceMillis = 5 * 60_000L

    suspend fun get(key: String, ttlMillis: Long, loader: suspend () -> ByteArray): FeedMessage {
        val now = clock()
        entries[key]?.let { if (now - it.fetchedAtMillis < ttlMillis) return it.feed }

        val lock = locks.computeIfAbsent(key) { Mutex() }
        lock.withLock {
            // Re-check under the lock — another coroutine may have refreshed.
            val fresh = entries[key]
            val nowLocked = clock()
            if (fresh != null && nowLocked - fresh.fetchedAtMillis < ttlMillis) return fresh.feed

            return try {
                val decoded = FeedMessage.ADAPTER.decode(loader())
                entries[key] = Entry(decoded, clock())
                decoded
            } catch (e: Throwable) {
                val stale = entries[key]
                if (stale != null && clock() - stale.fetchedAtMillis < staleGraceMillis) {
                    stale.feed
                } else {
                    throw e
                }
            }
        }
    }

    /** Cached entry count — exposed for tests/metrics. */
    fun size(): Int = entries.size
}
