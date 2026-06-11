package app.krail.bff.track

/**
 * Short-term, server-side memory of every stop seen for a live trip.
 *
 * NSW trims PASSED stops out of TripUpdates as a trip progresses, so a
 * single feed snapshot is an incomplete timeline. This memory accumulates
 * the union of stop_time_updates observed across feed refreshes; stops
 * that later disappear from the feed are re-attached to responses as
 * DEPARTED. The result: every poll is a complete snapshot of the run —
 * including for clients that join mid-trip (share links) or wake from
 * background and never saw the earlier stops themselves.
 *
 * Honest limits (documented in the handover): memory starts when the BFF
 * first observes the trip — stops trimmed before that are unrecoverable
 * server-side (the app overlays its planned stop list for those), and a
 * BFF restart clears it (stateless-service principle: memory is an
 * enhancement, never a correctness dependency).
 *
 * Bounded: LRU over trips, default 4000 entries (a full NSW day is ~2k
 * concurrent live trips), entries dropped after [ttlMillis].
 */
class TripStopMemory(
    private val maxTrips: Int = 4000,
    private val ttlMillis: Long = 8 * 3600_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class RememberedStop(
        val stopId: String,
        val sequence: Int?,
        val lastEstimatedEpochSec: Long,
    )

    private class Entry(var touchedAt: Long, val stops: LinkedHashMap<String, RememberedStop>)

    private val trips = object : LinkedHashMap<String, Entry>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > maxTrips
    }

    /**
     * Records the stops currently visible for [tripKey] and returns the
     * remembered stops that are NO LONGER in the feed (i.e. passed and
     * trimmed), in their original sequence order.
     */
    @Synchronized
    fun recordAndGetTrimmed(tripKey: String, current: List<RememberedStop>): List<RememberedStop> {
        val now = clock()
        // Opportunistic TTL sweep — cheap at this scale.
        trips.values.removeIf { now - it.touchedAt > ttlMillis }

        val entry = trips.getOrPut(tripKey) { Entry(now, LinkedHashMap()) }
        entry.touchedAt = now

        val currentIds = current.mapTo(HashSet()) { it.stopId }
        val trimmed = entry.stops.values.filter { it.stopId !in currentIds }

        current.forEach { entry.stops[it.stopId] = it }

        return trimmed.sortedWith(compareBy(nullsLast()) { it.sequence })
    }

    @Synchronized
    fun size(): Int = trips.size
}
