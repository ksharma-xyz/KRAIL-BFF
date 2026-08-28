package app.krail.bff.router

/**
 * Parses one GTFS feed from a [GtfsSource] into a [GtfsFeed].
 *
 * Only journey-planning columns are read. Rows referencing unknown trips,
 * stops, routes or services are dropped rather than failing the whole feed —
 * real VIC data contains minor referential slop and the router must survive
 * a weekly dataset refresh unattended.
 */
object GtfsParser {

    private const val DEFAULT_TRANSFER_SECONDS = 120

    /**
     * Hard cap per frequencies.txt row: a 10s headway across a 14h span is
     * ~5000 trips; anything beyond that is a corrupt row, not a timetable.
     */
    private const val MAX_TRIPS_PER_FREQUENCY_ROW = 5040

    fun parseFeed(source: GtfsSource): GtfsFeed {
        val (stops, stations, stopIndexById) = parseStops(source)
        val (routes, routeIndexById) = parseRoutes(source)
        val (serviceIds, services, serviceIndexById) = parseCalendars(source)
        var (trips, tripIndexById) = parseTrips(source, routeIndexById, serviceIndexById)
        var stopTimes = parseStopTimes(source, tripIndexById, stopIndexById)
        val frequencies = parseFrequencies(source, tripIndexById)
        if (frequencies.isNotEmpty()) {
            val expanded = expandFrequencies(trips, stopTimes, frequencies)
            trips = expanded.first
            stopTimes = expanded.second
        }
        val stationChildren = HashMap<String, MutableList<Int>>()
        stops.forEachIndexed { idx, s ->
            s.parentStation?.let { stationChildren.getOrPut(it) { ArrayList() }.add(idx) }
        }
        val transfers = parseTransfers(source, stopIndexById, stationChildren)
        return GtfsFeed(
            namespace = source.namespace,
            stops = stops,
            stations = stations,
            routes = routes,
            trips = trips,
            serviceIds = serviceIds,
            services = services,
            stopTimes = stopTimes,
            transfers = transfers,
        )
    }

    private fun open(source: GtfsSource, name: String): CsvReader? =
        source.open(name)?.let { CsvReader(it) }

    private fun require(source: GtfsSource, name: String): CsvReader =
        open(source, name)
            ?: throw IllegalArgumentException("GTFS feed '${source.namespace}': missing $name")

    private fun col(header: Map<String, Int>, name: String, file: String, namespace: String): Int =
        header[name]
            ?: throw IllegalArgumentException("GTFS feed '$namespace': $file has no column $name")

    private data class StopsResult(
        val stops: List<GtfsStop>,
        val stations: List<GtfsStation>,
        val indexById: Map<String, Int>,
    )

    private fun parseStops(source: GtfsSource): StopsResult {
        val stops = ArrayList<GtfsStop>()
        val stations = ArrayList<GtfsStation>()
        val indexById = HashMap<String, Int>()
        require(source, "stops.txt").use { csv ->
            if (!csv.readRow()) return StopsResult(stops, stations, indexById)
            val h = csv.headerMap()
            val cId = col(h, "stop_id", "stops.txt", source.namespace)
            val cName = col(h, "stop_name", "stops.txt", source.namespace)
            val cLat = col(h, "stop_lat", "stops.txt", source.namespace)
            val cLon = col(h, "stop_lon", "stops.txt", source.namespace)
            val cType = h["location_type"] ?: -1
            val cParent = h["parent_station"] ?: -1
            while (csv.readRow()) {
                if (csv.isBlank(cId)) continue
                when (csv.int(cType, 0)) {
                    0 -> {
                        val lat = csv.double(cLat, Double.NaN)
                        val lon = csv.double(cLon, Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) continue
                        val id = csv.string(cId)
                        if (!indexById.containsKey(id)) {
                            indexById[id] = stops.size
                            stops.add(
                                GtfsStop(
                                    id = id,
                                    name = csv.string(cName),
                                    lat = lat,
                                    lon = lon,
                                    parentStation = if (csv.isBlank(cParent)) null else csv.string(cParent),
                                )
                            )
                        }
                    }
                    1 -> stations.add(GtfsStation(csv.string(cId), csv.string(cName)))
                    // 2 (entrances), 3 (generic nodes), 4 (boarding areas): not routable
                }
            }
        }
        return StopsResult(stops, stations, indexById)
    }

    private fun parseRoutes(source: GtfsSource): Pair<List<GtfsRoute>, Map<String, Int>> {
        val routes = ArrayList<GtfsRoute>()
        val indexById = HashMap<String, Int>()
        require(source, "routes.txt").use { csv ->
            if (!csv.readRow()) return routes to indexById
            val h = csv.headerMap()
            val cId = col(h, "route_id", "routes.txt", source.namespace)
            val cShort = h["route_short_name"] ?: -1
            val cLong = h["route_long_name"] ?: -1
            val cType = col(h, "route_type", "routes.txt", source.namespace)
            while (csv.readRow()) {
                if (csv.isBlank(cId)) continue
                val id = csv.string(cId)
                if (indexById.containsKey(id)) continue
                indexById[id] = routes.size
                routes.add(
                    GtfsRoute(
                        id = id,
                        shortName = if (csv.isBlank(cShort)) "" else csv.string(cShort),
                        longName = if (csv.isBlank(cLong)) "" else csv.string(cLong),
                        // Extended types (400 metro rail, 701 bus, …) pass through as-is.
                        type = csv.int(cType, -1),
                    )
                )
            }
        }
        return routes to indexById
    }

    private data class CalendarsResult(
        val serviceIds: List<String>,
        val services: List<ServiceCalendar>,
        val indexById: Map<String, Int>,
    )

    private class CalendarAcc {
        var weekdayMask = 0
        var startDate = Int.MAX_VALUE
        var endDate = Int.MIN_VALUE
        val added = IntVec(4)
        val removed = IntVec(4)
    }

    private fun parseCalendars(source: GtfsSource): CalendarsResult {
        val acc = LinkedHashMap<String, CalendarAcc>()
        open(source, "calendar.txt")?.use { csv ->
            if (csv.readRow()) {
                val h = csv.headerMap()
                val cId = col(h, "service_id", "calendar.txt", source.namespace)
                val days = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
                    .map { col(h, it, "calendar.txt", source.namespace) }
                val cStart = col(h, "start_date", "calendar.txt", source.namespace)
                val cEnd = col(h, "end_date", "calendar.txt", source.namespace)
                while (csv.readRow()) {
                    if (csv.isBlank(cId)) continue
                    val a = acc.getOrPut(csv.string(cId)) { CalendarAcc() }
                    var mask = 0
                    days.forEachIndexed { bit, c -> if (csv.int(c, 0) == 1) mask = mask or (1 shl bit) }
                    a.weekdayMask = mask
                    a.startDate = csv.int(cStart, Int.MAX_VALUE)
                    a.endDate = csv.int(cEnd, Int.MIN_VALUE)
                }
            }
        }
        open(source, "calendar_dates.txt")?.use { csv ->
            if (csv.readRow()) {
                val h = csv.headerMap()
                val cId = col(h, "service_id", "calendar_dates.txt", source.namespace)
                val cDate = col(h, "date", "calendar_dates.txt", source.namespace)
                val cType = col(h, "exception_type", "calendar_dates.txt", source.namespace)
                while (csv.readRow()) {
                    if (csv.isBlank(cId)) continue
                    val a = acc.getOrPut(csv.string(cId)) { CalendarAcc() }
                    val date = csv.int(cDate, 0)
                    if (date == 0) continue
                    when (csv.int(cType, 0)) {
                        1 -> a.added.add(date)
                        2 -> a.removed.add(date)
                    }
                }
            }
        }
        if (acc.isEmpty()) {
            throw IllegalArgumentException(
                "GTFS feed '${source.namespace}': neither calendar.txt nor calendar_dates.txt present"
            )
        }
        val serviceIds = ArrayList<String>(acc.size)
        val services = ArrayList<ServiceCalendar>(acc.size)
        val indexById = HashMap<String, Int>(acc.size * 2)
        for ((id, a) in acc) {
            indexById[id] = services.size
            serviceIds.add(id)
            services.add(
                ServiceCalendar(
                    weekdayMask = a.weekdayMask,
                    startDate = a.startDate,
                    endDate = a.endDate,
                    addedDates = a.added.toArray().also { it.sort() },
                    removedDates = a.removed.toArray().also { it.sort() },
                )
            )
        }
        return CalendarsResult(serviceIds, services, indexById)
    }

    private fun parseTrips(
        source: GtfsSource,
        routeIndexById: Map<String, Int>,
        serviceIndexById: Map<String, Int>,
    ): Pair<List<GtfsTrip>, Map<String, Int>> {
        val trips = ArrayList<GtfsTrip>()
        val indexById = HashMap<String, Int>()
        require(source, "trips.txt").use { csv ->
            if (!csv.readRow()) return trips to indexById
            val h = csv.headerMap()
            val cRoute = col(h, "route_id", "trips.txt", source.namespace)
            val cService = col(h, "service_id", "trips.txt", source.namespace)
            val cTrip = col(h, "trip_id", "trips.txt", source.namespace)
            val cHeadsign = h["trip_headsign"] ?: -1
            while (csv.readRow()) {
                if (csv.isBlank(cTrip)) continue
                val routeIdx = routeIndexById[csv.string(cRoute)] ?: continue
                val serviceIdx = serviceIndexById[csv.string(cService)] ?: continue
                val id = csv.string(cTrip)
                if (indexById.containsKey(id)) continue
                indexById[id] = trips.size
                trips.add(
                    GtfsTrip(
                        id = id,
                        routeIdx = routeIdx,
                        serviceIdx = serviceIdx,
                        headsign = if (csv.isBlank(cHeadsign)) null else csv.string(cHeadsign),
                    )
                )
            }
        }
        return trips to indexById
    }

    private fun parseStopTimes(
        source: GtfsSource,
        tripIndexById: Map<String, Int>,
        stopIndexById: Map<String, Int>,
    ): StopTimesTable {
        val trip = IntVec(1 shl 16)
        val stop = IntVec(1 shl 16)
        val arr = IntVec(1 shl 16)
        val dep = IntVec(1 shl 16)
        val seq = IntVec(1 shl 16)
        val flags = ByteVec(1 shl 16)
        require(source, "stop_times.txt").use { csv ->
            if (!csv.readRow()) {
                return StopTimesTable(IntArray(0), IntArray(0), IntArray(0), IntArray(0), IntArray(0), ByteArray(0))
            }
            val h = csv.headerMap()
            val cTrip = col(h, "trip_id", "stop_times.txt", source.namespace)
            val cArr = col(h, "arrival_time", "stop_times.txt", source.namespace)
            val cDep = col(h, "departure_time", "stop_times.txt", source.namespace)
            val cStop = col(h, "stop_id", "stop_times.txt", source.namespace)
            val cSeq = col(h, "stop_sequence", "stop_times.txt", source.namespace)
            // pickup_type / drop_off_type: 1 = not available. Types 2/3
            // (phone agency / coordinate with driver) stay boardable — the
            // rider can, with arrangement; the router should not hide them.
            val cPickup = h["pickup_type"] ?: -1
            val cDropOff = h["drop_off_type"] ?: -1
            // Rows for one trip are contiguous in practice; cache the last
            // trip-id lookup to avoid a String + hash per row at 7.5M rows.
            var lastTripId: String? = null
            var lastTripIdx = -1
            while (csv.readRow()) {
                val tripIdx: Int
                if (lastTripId != null && csv.fieldEquals(cTrip, lastTripId)) {
                    tripIdx = lastTripIdx
                } else {
                    lastTripId = csv.string(cTrip)
                    lastTripIdx = tripIndexById[lastTripId] ?: -1
                    tripIdx = lastTripIdx
                }
                if (tripIdx < 0) continue
                val stopIdx = stopIndexById[csv.string(cStop)] ?: continue
                var a = csv.timeSeconds(cArr)
                var d = csv.timeSeconds(cDep)
                if (a < 0) a = d
                if (d < 0) d = a
                var f = 0
                if (cPickup >= 0 && csv.int(cPickup, 0) == 1) f = f or STOP_TIME_NO_PICKUP
                if (cDropOff >= 0 && csv.int(cDropOff, 0) == 1) f = f or STOP_TIME_NO_DROP_OFF
                trip.add(tripIdx)
                stop.add(stopIdx)
                arr.add(a)
                dep.add(d)
                seq.add(csv.int(cSeq, 0))
                flags.add(f.toByte())
            }
        }
        return StopTimesTable(
            trip.toArray(), stop.toArray(), arr.toArray(), dep.toArray(), seq.toArray(), flags.toArray()
        )
    }

    private class FrequencyRow(val tripIdx: Int, val startSec: Int, val endSec: Int, val headwaySec: Int)

    /**
     * frequencies.txt: trips repeated on a headway. Rows referencing unknown
     * trips, with malformed times, or with implausible headways (< 10s) are
     * dropped. `exact_times` changes only the semantics (1 = exact schedule
     * replication, 0 = headway-based service); both expand to concrete trips
     * departing every headway in [start_time, end_time).
     */
    private fun parseFrequencies(source: GtfsSource, tripIndexById: Map<String, Int>): List<FrequencyRow> {
        val rows = ArrayList<FrequencyRow>()
        open(source, "frequencies.txt")?.use { csv ->
            if (!csv.readRow()) return rows
            val h = csv.headerMap()
            val cTrip = h["trip_id"] ?: return rows
            val cStart = h["start_time"] ?: return rows
            val cEnd = h["end_time"] ?: return rows
            val cHeadway = h["headway_secs"] ?: return rows
            while (csv.readRow()) {
                val tripIdx = tripIndexById[csv.string(cTrip)] ?: continue
                val start = csv.timeSeconds(cStart)
                val end = csv.timeSeconds(cEnd)
                val headway = csv.int(cHeadway, 0)
                if (start < 0 || end <= start || headway < 10) continue
                rows.add(FrequencyRow(tripIdx, start, end, headway))
            }
        }
        return rows
    }

    /**
     * Expands frequency-based trips into concrete trips: for each frequencies
     * row, one clone of the template trip per headway step in
     * [start_time, end_time), times shifted so the first stop's departure
     * lands on the step. Template trips keep their trips.txt entry but lose
     * their stop_times rows (per spec their absolute times are only
     * travel-time offsets, not a schedulable trip); the builder drops
     * row-less trips.
     */
    private fun expandFrequencies(
        trips: List<GtfsTrip>,
        stopTimes: StopTimesTable,
        frequencies: List<FrequencyRow>,
    ): Pair<List<GtfsTrip>, StopTimesTable> {
        val frequencyTrips = HashSet<Int>(frequencies.size * 2)
        for (f in frequencies) frequencyTrips.add(f.tripIdx)

        // Rows per template trip, in stop_sequence order.
        val rowsByTrip = HashMap<Int, IntVec>()
        for (r in 0 until stopTimes.size) {
            val t = stopTimes.trip[r]
            if (t in frequencyTrips) rowsByTrip.getOrPut(t) { IntVec(8) }.add(r)
        }
        for (rows in rowsByTrip.values) {
            // Nearly always pre-sorted; insertion sort on stop_sequence.
            for (i in 1 until rows.size) {
                val row = rows[i]
                val key = stopTimes.seq[row]
                var j = i - 1
                while (j >= 0 && stopTimes.seq[rows[j]] > key) {
                    rows.data[j + 1] = rows.data[j]
                    j--
                }
                rows.data[j + 1] = row
            }
        }

        val outTrips = ArrayList<GtfsTrip>(trips.size)
        val trip = IntVec(stopTimes.size)
        val stop = IntVec(stopTimes.size)
        val arr = IntVec(stopTimes.size)
        val dep = IntVec(stopTimes.size)
        val seq = IntVec(stopTimes.size)
        val flags = ByteVec(stopTimes.size)

        // Scheduled (non-frequency) trips keep their rows and indices as-is.
        outTrips.addAll(trips)
        for (r in 0 until stopTimes.size) {
            if (stopTimes.trip[r] in frequencyTrips) continue
            trip.add(stopTimes.trip[r])
            stop.add(stopTimes.stop[r])
            arr.add(stopTimes.arr[r])
            dep.add(stopTimes.dep[r])
            seq.add(stopTimes.seq[r])
            flags.add(stopTimes.flags[r])
        }

        // Overlapping/duplicate rows for one template must not clone the same
        // departure twice (spec forbids overlap; feeds ship slop anyway).
        val seenStarts = HashMap<Int, HashSet<Int>>()
        for (f in frequencies) {
            val rows = rowsByTrip[f.tripIdx] ?: continue
            if (rows.size < 2) continue
            // Anchor: the template's first-stop departure — GTFS start_time is
            // when the vehicle departs the first stop. (Blank endpoints make
            // the template unusable — the builder would drop such trips anyway.)
            val anchor = stopTimes.dep[rows[0]]
            if (anchor < 0) continue
            val template = trips[f.tripIdx]
            val starts = seenStarts.getOrPut(f.tripIdx) { HashSet() }
            var start = f.startSec
            var generated = 0
            while (start < f.endSec && generated < MAX_TRIPS_PER_FREQUENCY_ROW) {
                if (!starts.add(start)) {
                    start += f.headwaySec
                    continue
                }
                val newTripIdx = outTrips.size
                outTrips.add(
                    GtfsTrip(
                        id = "${template.id}#${gtfsTime(start)}",
                        routeIdx = template.routeIdx,
                        serviceIdx = template.serviceIdx,
                        headsign = template.headsign,
                    )
                )
                val shift = start - anchor
                for (i in 0 until rows.size) {
                    val r = rows[i]
                    trip.add(newTripIdx)
                    stop.add(stopTimes.stop[r])
                    arr.add(if (stopTimes.arr[r] < 0) -1 else stopTimes.arr[r] + shift)
                    dep.add(if (stopTimes.dep[r] < 0) -1 else stopTimes.dep[r] + shift)
                    seq.add(stopTimes.seq[r])
                    flags.add(stopTimes.flags[r])
                }
                start += f.headwaySec
                generated++
            }
        }

        return outTrips to StopTimesTable(
            trip.toArray(), stop.toArray(), arr.toArray(), dep.toArray(), seq.toArray(), flags.toArray()
        )
    }

    private fun gtfsTime(sec: Int): String =
        "%02d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60)

    private fun parseTransfers(
        source: GtfsSource,
        stopIndexById: Map<String, Int>,
        stationChildren: Map<String, MutableList<Int>>,
    ): List<GtfsTransfer> {
        val transfers = ArrayList<GtfsTransfer>()
        open(source, "transfers.txt")?.use { csv ->
            if (!csv.readRow()) return transfers
            val h = csv.headerMap()
            val cFrom = h["from_stop_id"] ?: return transfers
            val cTo = h["to_stop_id"] ?: return transfers
            val cType = h["transfer_type"] ?: -1
            val cTime = h["min_transfer_time"] ?: -1
            val cFromTrip = h["from_trip_id"] ?: -1
            val cToTrip = h["to_trip_id"] ?: -1
            while (csv.readRow()) {
                // Types 3 (not possible) and 4/5 (in-seat continuations) are not
                // footpaths. VIC folder 2's transfers.txt is entirely type 4.
                if (csv.int(cType, 0) > 2) continue
                // Trip-qualified rows are continuations, not stop transfers.
                if (!csv.isBlank(cFromTrip) || !csv.isBlank(cToTrip)) continue
                if (csv.isBlank(cFrom) || csv.isBlank(cTo)) continue
                val seconds = csv.int(cTime, DEFAULT_TRANSFER_SECONDS)
                val fromStops = resolveTransferEndpoint(csv.string(cFrom), stopIndexById, stationChildren)
                val toStops = resolveTransferEndpoint(csv.string(cTo), stopIndexById, stationChildren)
                for (f in fromStops) for (t in toStops) {
                    if (f != t) transfers.add(GtfsTransfer(f, t, seconds))
                }
            }
        }
        return transfers
    }

    private fun resolveTransferEndpoint(
        id: String,
        stopIndexById: Map<String, Int>,
        stationChildren: Map<String, MutableList<Int>>,
    ): IntArray {
        stopIndexById[id]?.let { return intArrayOf(it) }
        stationChildren[id]?.let { return it.toIntArray() }
        return IntArray(0)
    }
}
