package app.krail.bff.router

import java.time.LocalDate
import java.util.BitSet

/**
 * Compact, immutable journey-planning model for one city, built from one or
 * more GTFS feeds. Everything is flat primitive arrays — no per-StopTime
 * objects — so ~10M VIC stop-time rows fit comfortably on a small dyno.
 *
 * Trips are grouped into patterns (same route + exact stop sequence); trips
 * within a pattern are sorted by departure at the first stop. Times for
 * pattern p, local trip t, stop position i live at
 * `patternTimesBase[p] + t * stopCount(p) + i` in [arrivals]/[departures].
 */
class RouterModel(
    val meta: RouterModelMeta,
    // Stops (routable only)
    val stopIds: Array<String>,
    val stopNames: Array<String>,
    val stopLats: DoubleArray,
    val stopLons: DoubleArray,
    // Routes
    val routeIds: Array<String>,
    val routeShortNames: Array<String>,
    val routeLongNames: Array<String>,
    val routeTypes: IntArray,
    // Services
    val services: Array<ServiceCalendar>,
    // Patterns
    val patternRoute: IntArray,
    val patternStopsOffset: IntArray, // size P+1, indexes patternStops
    val patternStops: IntArray,
    val patternTripsOffset: IntArray, // size P+1, indexes trip-level arrays
    val patternTimesBase: IntArray, // size P, base offset into arrivals/departures
    /**
     * Parallel to [patternStops]: true when departures at that stop position
     * are non-decreasing across the pattern's (first-stop-sorted) trips —
     * binary search for a boarding is then exact. Positions with overtaking
     * fall back to a linear scan in the router (27% of VIC patterns have at
     * least one inverted position, so this must be per-position for speed).
     */
    val patternFifo: BooleanArray, // size = patternStops.size
    // Trips (global order: grouped by pattern, sorted by first-stop departure)
    val tripIds: Array<String>,
    val tripServices: IntArray,
    val tripHeadsigns: Array<String?>,
    // Stop times
    val arrivals: IntArray,
    val departures: IntArray,
    /**
     * Parallel to [arrivals]/[departures]: [STOP_TIME_NO_PICKUP] /
     * [STOP_TIME_NO_DROP_OFF] bits per call. The router must not board at
     * no-pickup calls nor alight at no-drop-off calls (SEQ sets these on
     * ~0.25% of rows mid-trip; MBTA/VBB/PID use them too).
     */
    val stopTimeFlags: ByteArray,
    // Stop → (pattern, position) adjacency
    val stopAdjOffset: IntArray, // size S+1
    val adjPattern: IntArray,
    val adjPosition: IntArray,
    // Footpath transfers
    val transferOffset: IntArray, // size S+1
    val transferTarget: IntArray,
    val transferSeconds: IntArray,
    /** Raw stop id or station id → routable stop indices. */
    val stopIdLookup: Map<String, IntArray>,
) {
    val stopCount: Int get() = stopIds.size
    val patternCount: Int get() = patternRoute.size
    val tripCount: Int get() = tripIds.size

    fun patternStopCount(p: Int): Int = patternStopsOffset[p + 1] - patternStopsOffset[p]

    fun patternStopAt(p: Int, pos: Int): Int = patternStops[patternStopsOffset[p] + pos]

    /** Stop indices a public id resolves to; empty when unknown. */
    fun stopsForId(id: String): IntArray = stopIdLookup[id] ?: EMPTY

    /** Bit set over service indices active on [date] (calendar + exceptions). */
    fun activeServices(date: LocalDate): BitSet {
        val bits = BitSet(services.size)
        for (i in services.indices) if (services[i].activeOn(date)) bits.set(i)
        return bits
    }

    private companion object {
        val EMPTY = IntArray(0)
    }
}

class RouterModelMeta(
    val sourceLabel: String,
    /** yyyymmdd span covered by the dataset's calendars. */
    val calendarStart: Int,
    val calendarEnd: Int,
    /** Set by the offline build tool; 0 when unknown. Core never reads a clock. */
    val builtAtEpochSec: Long,
)
