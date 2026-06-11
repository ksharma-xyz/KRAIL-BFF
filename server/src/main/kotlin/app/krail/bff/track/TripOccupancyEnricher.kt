package app.krail.bff.track

import app.krail.bff.client.nsw.NswClient
import app.krail.bff.proto.OccupancyInfo
import app.krail.bff.proto.TrackRequest
import com.codahale.metrics.MetricRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId

/**
 * Per-station expected occupancy (T1.6, design §3a) — the one optional
 * Trip Planner call in tracking. Answers "how full will it be from MY
 * station?" via `stopSequence[].properties.occupancy`, distinct from the
 * live PassLoad strip (which is the vehicle's state right now).
 *
 * Hard validation rule: the TP response is enrichment ONLY when the
 * journey leg's RealtimeTripId equals the locked trip_id. A re-planned
 * journey (different trip) is discarded, never substituted — tracking
 * never re-plans.
 *
 * Best-effort + budget-aware: results (including misses and failures)
 * are cached per (trip_id, service_date), so each tracked trip costs at
 * most one Trip Planner call per [ttlMillis] against NSW_DAILY_BUDGET.
 * Bounded LRU, same pattern as TripStopMemory.
 */
class TripOccupancyEnricher(
    private val nsw: NswClient,
    private val metrics: MetricRegistry,
    private val maxTrips: Int = 4000,
    private val ttlMillis: Long = 6 * 3_600_000L,
    private val failureTtlMillis: Long = 5 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger(TripOccupancyEnricher::class.java)
    private val sydney = ZoneId.of("Australia/Sydney")
    private val fetchMutex = Mutex()

    /** levels == null marks a failed fetch (retried after [failureTtlMillis]). */
    private class Entry(var at: Long, val levels: Map<String, OccupancyInfo.Level>?)

    private val cache = object : LinkedHashMap<String, Entry>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > maxTrips
    }

    /**
     * stop_id → expected level for the leg's locked trip. Empty map when
     * the data doesn't exist (no TP match, no occupancy fields, failure) —
     * callers emit nothing in that case.
     */
    suspend fun expectedOccupancy(leg: TrackRequest.TrackLeg): Map<String, OccupancyInfo.Level> {
        val tripId = leg.realtime_trip_id.trim()
        if (tripId.isEmpty() || leg.origin_stop_id.isBlank() || leg.destination_stop_id.isBlank()) {
            return emptyMap()
        }
        val key = "$tripId@${leg.service_date}"

        cached(key)?.let { return it }
        // One TP call at a time server-wide: concurrent first polls for the
        // same trip collapse onto the cache; cross-trip serialization is
        // fine at this traffic level and kind to the NSW budget.
        return fetchMutex.withLock {
            cached(key)?.let { return it }
            val levels = try {
                fetch(leg, tripId).also {
                    metrics.counter(if (it.isEmpty()) "track.expected_occupancy.miss" else "track.expected_occupancy.hit").inc()
                }
            } catch (e: Throwable) {
                logger.warn("track: expected-occupancy fetch failed for {}: {}", key, e.message)
                metrics.counter("track.expected_occupancy.error").inc()
                null
            }
            synchronized(cache) { cache[key] = Entry(clock(), levels) }
            levels ?: emptyMap()
        }
    }

    private fun cached(key: String): Map<String, OccupancyInfo.Level>? {
        synchronized(cache) {
            val entry = cache[key] ?: return null
            val ttl = if (entry.levels == null) failureTtlMillis else ttlMillis
            if (clock() - entry.at > ttl) return null
            return entry.levels ?: emptyMap()
        }
    }

    private suspend fun fetch(leg: TrackRequest.TrackLeg, tripId: String): Map<String, OccupancyInfo.Level> {
        // Anchor the TP query on the planned departure so the locked trip is
        // inside the returned window; without a time TP plans "now", which
        // mid-journey rarely contains the boarded trip.
        val departure = runCatching { Instant.parse(leg.planned_departure_utc).atZone(sydney) }.getOrNull()
        val response = nsw.getTrip(
            originStopId = leg.origin_stop_id,
            destinationStopId = leg.destination_stop_id,
            depArr = "dep",
            date = leg.service_date.takeIf { it.isNotBlank() },
            time = departure?.let { "%02d%02d".format(it.hour, it.minute) },
        )

        // The validation rule: only the leg running THIS trip_id counts.
        val match = response.journeys.orEmpty()
            .asSequence()
            .flatMap { it.legs.orEmpty() }
            .firstOrNull { it.transportation?.properties?.realtimeTripId?.trim() == tripId }
        if (match == null) {
            metrics.counter("track.expected_occupancy.replan_discarded").inc()
            return emptyMap()
        }

        return buildMap {
            match.stopSequence.orEmpty().forEach { stop ->
                val id = stop.id?.takeIf { it.isNotBlank() } ?: return@forEach
                val level = mapOccupancy(stop.properties?.occupancy) ?: return@forEach
                put(id, level)
            }
        }
    }

    companion object {
        /**
         * Trip Planner occupancy strings → contract level. NSW is not
         * documented here; observed values plus the GTFS-style names,
         * mapped defensively (unknown strings yield null, not a guess).
         */
        fun mapOccupancy(value: String?): OccupancyInfo.Level? = when (value?.trim()?.uppercase()) {
            "EMPTY" -> OccupancyInfo.Level.EMPTY
            "MANY_SEATS", "MANY_SEATS_AVAILABLE" -> OccupancyInfo.Level.MANY_SEATS_AVAILABLE
            "FEW_SEATS", "FEW_SEATS_AVAILABLE" -> OccupancyInfo.Level.FEW_SEATS_AVAILABLE
            "STANDING_ONLY", "STANDING_ROOM_ONLY" -> OccupancyInfo.Level.STANDING_ROOM_ONLY
            "CRUSHED", "CRUSHED_STANDING_ROOM_ONLY" -> OccupancyInfo.Level.CRUSHED_STANDING_ROOM_ONLY
            "FULL" -> OccupancyInfo.Level.FULL
            "NOT_ACCEPTING_PASSENGERS" -> OccupancyInfo.Level.NOT_ACCEPTING_PASSENGERS
            else -> null
        }
    }
}
