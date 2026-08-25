package app.krail.bff.router

import java.time.LocalDate

/**
 * One parsed GTFS feed, reduced to the columns journey planning needs.
 * All cross-references are feed-local integer indices.
 */
class GtfsFeed(
    val namespace: String,
    /** Routable stops (location_type 0). */
    val stops: List<GtfsStop>,
    /** Stations (location_type 1); never appear in stop_times. */
    val stations: List<GtfsStation>,
    val routes: List<GtfsRoute>,
    val trips: List<GtfsTrip>,
    val serviceIds: List<String>,
    val services: List<ServiceCalendar>,
    val stopTimes: StopTimesTable,
    /** Usable stop-to-stop transfers (types 0/1/2), station ids expanded to children. */
    val transfers: List<GtfsTransfer>,
)

class GtfsStop(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val parentStation: String?,
)

class GtfsStation(val id: String, val name: String)

class GtfsRoute(val id: String, val shortName: String, val longName: String, val type: Int)

class GtfsTrip(val id: String, val routeIdx: Int, val serviceIdx: Int, val headsign: String?)

class GtfsTransfer(val fromStop: Int, val toStop: Int, val seconds: Int)

/**
 * Flat stop_times columns; one entry per row. Times are seconds since the
 * service-day midnight (may exceed 24h), -1 when the feed left them blank.
 */
class StopTimesTable(
    val trip: IntArray,
    val stop: IntArray,
    val arr: IntArray,
    val dep: IntArray,
    val seq: IntArray,
) {
    val size: Int get() = trip.size
}

/**
 * Resolved service calendar: calendar.txt row merged with its
 * calendar_dates.txt exceptions. Dates are yyyymmdd ints.
 */
class ServiceCalendar(
    val weekdayMask: Int, // bit 0 = Monday … bit 6 = Sunday
    val startDate: Int,
    val endDate: Int,
    val addedDates: IntArray, // sorted
    val removedDates: IntArray, // sorted
) {
    fun activeOn(date: LocalDate): Boolean {
        val d = date.toGtfsDate()
        if (java.util.Arrays.binarySearch(removedDates, d) >= 0) return false
        if (java.util.Arrays.binarySearch(addedDates, d) >= 0) return true
        if (d < startDate || d > endDate) return false
        return (weekdayMask shr (date.dayOfWeek.value - 1)) and 1 == 1
    }
}

fun LocalDate.toGtfsDate(): Int = year * 10000 + monthValue * 100 + dayOfMonth
