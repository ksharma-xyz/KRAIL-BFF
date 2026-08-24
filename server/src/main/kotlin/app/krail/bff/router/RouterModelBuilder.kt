package app.krail.bff.router

import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class TransferConfig(
    val radiusMeters: Double = 350.0,
    val walkSpeedMps: Double = 1.33,
    val bufferSeconds: Int = 60,
    /** Same raw stop id in two feeds closer than this = same physical stop. */
    val mergeDistanceMeters: Double = 50.0,
)

/**
 * Builds a [RouterModel] from parsed feeds. Offline-time code: favours
 * clarity over allocation thrift except where row counts (millions) force
 * primitive arrays.
 */
object RouterModelBuilder {

    fun build(
        feeds: List<GtfsFeed>,
        sourceLabel: String,
        builtAtEpochSec: Long = 0L,
        config: TransferConfig = TransferConfig(),
    ): RouterModel {
        // -- Stops: merge across feeds on (raw id, proximity) ----------------
        val stopIds = ArrayList<String>()
        val stopNames = ArrayList<String>()
        val stopLats = DoubleVec()
        val stopLons = DoubleVec()
        val indexByRawId = HashMap<String, IntVec>()
        val feedStopGlobal = Array(feeds.size) { IntArray(feeds[it].stops.size) }

        feeds.forEachIndexed { f, feed ->
            feed.stops.forEachIndexed { i, s ->
                val candidates = indexByRawId[s.id]
                var global = -1
                if (candidates != null) {
                    for (c in 0 until candidates.size) {
                        val g = candidates[c]
                        if (haversineMeters(stopLats[g], stopLons[g], s.lat, s.lon) <= config.mergeDistanceMeters) {
                            global = g
                            break
                        }
                    }
                }
                if (global < 0) {
                    global = stopIds.size
                    stopIds.add(s.id)
                    stopNames.add(s.name)
                    stopLats.add(s.lat)
                    stopLons.add(s.lon)
                    indexByRawId.getOrPut(s.id) { IntVec(2) }.add(global)
                }
                feedStopGlobal[f][i] = global
            }
        }
        val stopCount = stopIds.size

        // -- Routes / services: plain concatenation --------------------------
        val routeIds = ArrayList<String>()
        val routeShortNames = ArrayList<String>()
        val routeLongNames = ArrayList<String>()
        val routeTypes = IntVec()
        val feedRouteOffset = IntArray(feeds.size)
        val services = ArrayList<ServiceCalendar>()
        val feedServiceOffset = IntArray(feeds.size)
        feeds.forEachIndexed { f, feed ->
            feedRouteOffset[f] = routeIds.size
            feed.routes.forEach {
                routeIds.add(it.id)
                routeShortNames.add(it.shortName)
                routeLongNames.add(it.longName)
                routeTypes.add(it.type)
            }
            feedServiceOffset[f] = services.size
            services.addAll(feed.services)
        }

        // -- Trips → patterns ------------------------------------------------
        val patterns = LinkedHashMap<PatternKey, ArrayList<TripAgg>>()
        feeds.forEachIndexed { f, feed ->
            aggregateFeedTrips(feed, feedStopGlobal[f], feedRouteOffset[f], feedServiceOffset[f], patterns)
        }

        val patternCount = patterns.size
        val patternRoute = IntArray(patternCount)
        val patternStopsOffset = IntArray(patternCount + 1)
        val patternTripsOffset = IntArray(patternCount + 1)
        val patternTimesBase = IntArray(patternCount)
        val patternKeys = patterns.keys.toTypedArray()
        var totalPatternStops = 0
        var totalTrips = 0
        var totalTimes = 0L
        patternKeys.forEachIndexed { p, key ->
            val trips = patterns[key]!!
            patternRoute[p] = key.route
            patternStopsOffset[p] = totalPatternStops
            patternTripsOffset[p] = totalTrips
            totalPatternStops += key.stops.size
            totalTrips += trips.size
            totalTimes += key.stops.size.toLong() * trips.size
        }
        patternStopsOffset[patternCount] = totalPatternStops
        patternTripsOffset[patternCount] = totalTrips
        require(totalTimes <= Int.MAX_VALUE) { "stop time table too large: $totalTimes" }

        val patternStops = IntArray(totalPatternStops)
        val tripIds = arrayOfNulls<String>(totalTrips)
        val tripServices = IntArray(totalTrips)
        val tripHeadsigns = arrayOfNulls<String>(totalTrips)
        val arrivals = IntArray(totalTimes.toInt())
        val departures = IntArray(totalTimes.toInt())
        var timesBase = 0
        patternKeys.forEachIndexed { p, key ->
            key.stops.copyInto(patternStops, patternStopsOffset[p])
            patternTimesBase[p] = timesBase
            val trips = patterns[key]!!
            trips.sortBy { it.dep[0] }
            val nStops = key.stops.size
            trips.forEachIndexed { tLocal, trip ->
                val g = patternTripsOffset[p] + tLocal
                tripIds[g] = trip.tripId
                tripServices[g] = trip.service
                tripHeadsigns[g] = trip.headsign
                trip.arr.copyInto(arrivals, timesBase + tLocal * nStops)
                trip.dep.copyInto(departures, timesBase + tLocal * nStops)
            }
            timesBase += nStops * trips.size
        }
        patterns.clear()

        // -- Stop → (pattern, position) adjacency ----------------------------
        val adjCounts = IntArray(stopCount)
        for (s in patternStops) adjCounts[s]++
        val stopAdjOffset = IntArray(stopCount + 1)
        for (s in 0 until stopCount) stopAdjOffset[s + 1] = stopAdjOffset[s] + adjCounts[s]
        val adjPattern = IntArray(totalPatternStops)
        val adjPosition = IntArray(totalPatternStops)
        val adjFill = IntArray(stopCount)
        for (p in 0 until patternCount) {
            val off = patternStopsOffset[p]
            val n = patternStopsOffset[p + 1] - off
            for (pos in 0 until n) {
                val s = patternStops[off + pos]
                val slot = stopAdjOffset[s] + adjFill[s]++
                adjPattern[slot] = p
                adjPosition[slot] = pos
            }
        }

        // -- Footpath transfers ----------------------------------------------
        val transferSecByPair = HashMap<Long, Int>()
        feeds.forEachIndexed { f, feed ->
            for (t in feed.transfers) {
                val from = feedStopGlobal[f][t.fromStop]
                val to = feedStopGlobal[f][t.toStop]
                if (from == to) continue
                val key = pairKey(from, to)
                val prev = transferSecByPair[key]
                if (prev == null || t.seconds < prev) transferSecByPair[key] = t.seconds
            }
        }
        generateProximityTransfers(
            stopLats.toArray(), stopLons.toArray(), config, transferSecByPair
        )

        val transferCounts = IntArray(stopCount)
        for (key in transferSecByPair.keys) transferCounts[(key ushr 32).toInt()]++
        val transferOffset = IntArray(stopCount + 1)
        for (s in 0 until stopCount) transferOffset[s + 1] = transferOffset[s] + transferCounts[s]
        val transferTarget = IntArray(transferSecByPair.size)
        val transferSeconds = IntArray(transferSecByPair.size)
        val transferFill = IntArray(stopCount)
        for ((key, sec) in transferSecByPair) {
            val from = (key ushr 32).toInt()
            val slot = transferOffset[from] + transferFill[from]++
            transferTarget[slot] = (key and 0xffffffffL).toInt()
            transferSeconds[slot] = sec
        }

        // -- Public id lookup: raw stop ids + station ids → stop indices -----
        val lookup = HashMap<String, IntVec>()
        for ((raw, vec) in indexByRawId) lookup[raw] = vec
        feeds.forEachIndexed { f, feed ->
            val childrenByStation = HashMap<String, IntVec>()
            feed.stops.forEachIndexed { i, s ->
                s.parentStation?.let {
                    childrenByStation.getOrPut(it) { IntVec(4) }.add(feedStopGlobal[f][i])
                }
            }
            for (station in feed.stations) {
                val children = childrenByStation[station.id] ?: continue
                val vec = lookup.getOrPut(station.id) { IntVec(children.size) }
                for (c in 0 until children.size) vec.add(children[c])
            }
        }
        val stopIdLookup = HashMap<String, IntArray>(lookup.size * 2)
        for ((id, vec) in lookup) stopIdLookup[id] = vec.toArray().distinct().toIntArray()

        // -- Calendar span for meta ------------------------------------------
        var calStart = Int.MAX_VALUE
        var calEnd = Int.MIN_VALUE
        for (svc in services) {
            if (svc.startDate != Int.MAX_VALUE) calStart = minOf(calStart, svc.startDate)
            if (svc.endDate != Int.MIN_VALUE) calEnd = max(calEnd, svc.endDate)
            if (svc.addedDates.isNotEmpty()) {
                calStart = minOf(calStart, svc.addedDates.first())
                calEnd = max(calEnd, svc.addedDates.last())
            }
        }

        @Suppress("UNCHECKED_CAST")
        return RouterModel(
            meta = RouterModelMeta(sourceLabel, calStart, calEnd, builtAtEpochSec),
            stopIds = stopIds.toTypedArray(),
            stopNames = stopNames.toTypedArray(),
            stopLats = stopLats.toArray(),
            stopLons = stopLons.toArray(),
            routeIds = routeIds.toTypedArray(),
            routeShortNames = routeShortNames.toTypedArray(),
            routeLongNames = routeLongNames.toTypedArray(),
            routeTypes = routeTypes.toArray(),
            services = services.toTypedArray(),
            patternRoute = patternRoute,
            patternStopsOffset = patternStopsOffset,
            patternStops = patternStops,
            patternTripsOffset = patternTripsOffset,
            patternTimesBase = patternTimesBase,
            tripIds = tripIds as Array<String>,
            tripServices = tripServices,
            tripHeadsigns = tripHeadsigns,
            arrivals = arrivals,
            departures = departures,
            stopAdjOffset = stopAdjOffset,
            adjPattern = adjPattern,
            adjPosition = adjPosition,
            transferOffset = transferOffset,
            transferTarget = transferTarget,
            transferSeconds = transferSeconds,
            stopIdLookup = stopIdLookup,
        )
    }

    // Groups one feed's stop_times into per-trip sequences, repairs times,
    // and buckets trips into patterns keyed by (route, exact stop sequence).
    private fun aggregateFeedTrips(
        feed: GtfsFeed,
        stopGlobal: IntArray,
        routeOffset: Int,
        serviceOffset: Int,
        patterns: LinkedHashMap<PatternKey, ArrayList<TripAgg>>,
    ) {
        val st = feed.stopTimes
        val nTrips = feed.trips.size
        val counts = IntArray(nTrips)
        for (r in 0 until st.size) counts[st.trip[r]]++
        val offsets = IntArray(nTrips + 1)
        for (t in 0 until nTrips) offsets[t + 1] = offsets[t] + counts[t]
        val rowOf = IntArray(st.size)
        val fill = IntArray(nTrips)
        for (r in 0 until st.size) {
            val t = st.trip[r]
            rowOf[offsets[t] + fill[t]++] = r
        }

        for (t in 0 until nTrips) {
            val from = offsets[t]
            val to = offsets[t + 1]
            val n = to - from
            if (n < 2) continue
            // Rows are nearly always pre-sorted by stop_sequence; insertion sort.
            for (i in from + 1 until to) {
                val row = rowOf[i]
                val key = st.seq[row]
                var j = i - 1
                while (j >= from && st.seq[rowOf[j]] > key) {
                    rowOf[j + 1] = rowOf[j]
                    j--
                }
                rowOf[j + 1] = row
            }
            val stops = IntArray(n)
            val arr = IntArray(n)
            val dep = IntArray(n)
            for (i in 0 until n) {
                val row = rowOf[from + i]
                stops[i] = stopGlobal[st.stop[row]]
                arr[i] = st.arr[row]
                dep[i] = st.dep[row]
            }
            if (!repairTimes(arr, dep)) continue
            val trip = feed.trips[t]
            val key = PatternKey(routeOffset + trip.routeIdx, stops)
            patterns.getOrPut(key) { ArrayList() }.add(
                TripAgg(trip.id, serviceOffset + trip.serviceIdx, trip.headsign, arr, dep)
            )
        }
    }

    /**
     * Fills blank (-1) times by linear interpolation between timepoints and
     * clamps out-of-order values to be non-decreasing. Returns false when the
     * trip is unusable (endpoint times missing).
     */
    private fun repairTimes(arr: IntArray, dep: IntArray): Boolean {
        val n = arr.size
        if (arr[0] < 0 || arr[n - 1] < 0) return false
        var i = 0
        while (i < n) {
            if (arr[i] >= 0) {
                i++
                continue
            }
            var j = i
            while (arr[j] < 0) j++
            val prevDep = dep[i - 1]
            val nextArr = arr[j]
            val gap = j - (i - 1)
            for (k in i until j) {
                val v = prevDep + ((nextArr - prevDep).toLong() * (k - (i - 1)) / gap).toInt()
                arr[k] = v
                dep[k] = v
            }
            i = j
        }
        for (k in 0 until n) {
            if (k > 0 && arr[k] < dep[k - 1]) arr[k] = dep[k - 1]
            if (dep[k] < arr[k]) dep[k] = arr[k]
        }
        return true
    }

    private fun generateProximityTransfers(
        lats: DoubleArray,
        lons: DoubleArray,
        config: TransferConfig,
        out: HashMap<Long, Int>,
    ) {
        val n = lats.size
        if (n == 0) return
        var meanLat = 0.0
        for (v in lats) meanLat += v
        meanLat /= n
        val cosLat = max(0.2, cos(Math.toRadians(meanLat)))
        val cellLatDeg = config.radiusMeters / 111_320.0
        val cellLonDeg = config.radiusMeters / (111_320.0 * cosLat)

        val grid = HashMap<Long, IntVec>()
        fun cellKey(cx: Long, cy: Long) = (cx shl 32) xor (cy and 0xffffffffL)
        for (i in 0 until n) {
            val cx = floor(lats[i] / cellLatDeg).toLong()
            val cy = floor(lons[i] / cellLonDeg).toLong()
            grid.getOrPut(cellKey(cx, cy)) { IntVec(8) }.add(i)
        }
        for (i in 0 until n) {
            val cx = floor(lats[i] / cellLatDeg).toLong()
            val cy = floor(lons[i] / cellLonDeg).toLong()
            for (dx in -1..1) for (dy in -1..1) {
                val bucket = grid[cellKey(cx + dx, cy + dy)] ?: continue
                for (b in 0 until bucket.size) {
                    val j = bucket[b]
                    if (j <= i) continue
                    val d = haversineMeters(lats[i], lons[i], lats[j], lons[j])
                    if (d > config.radiusMeters) continue
                    val sec = ceil(d / config.walkSpeedMps).toInt() + config.bufferSeconds
                    // Curated transfers.txt entries win over generated ones.
                    out.putIfAbsent(pairKey(i, j), sec)
                    out.putIfAbsent(pairKey(j, i), sec)
                }
            }
        }
    }

    private fun pairKey(from: Int, to: Int): Long = (from.toLong() shl 32) or (to.toLong() and 0xffffffffL)

    private class PatternKey(val route: Int, val stops: IntArray) {
        private val hash = 31 * route + stops.contentHashCode()
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean =
            other is PatternKey && other.route == route && other.stops.contentEquals(stops)
    }

    private class TripAgg(
        val tripId: String,
        val service: Int,
        val headsign: String?,
        val arr: IntArray,
        val dep: IntArray,
    )

    private class DoubleVec {
        private var data = DoubleArray(1024)
        var size = 0
            private set

        fun add(v: Double) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        operator fun get(i: Int): Double = data[i]

        fun toArray(): DoubleArray = data.copyOf(size)
    }
}

private const val EARTH_RADIUS_M = 6_371_000.0

fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
}
