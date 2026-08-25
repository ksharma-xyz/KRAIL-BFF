package app.krail.bff.mapper

import app.krail.bff.proto.Coord
import app.krail.bff.proto.JourneyCardInfo
import app.krail.bff.proto.JourneyList
import app.krail.bff.proto.Leg
import app.krail.bff.proto.Stop
import app.krail.bff.proto.TransportLeg
import app.krail.bff.proto.TransportModeLine
import app.krail.bff.proto.WalkingLeg
import app.krail.bff.router.Journey
import app.krail.bff.router.JourneyLeg
import app.krail.bff.router.RouterModel
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Maps router [Journey]s to the JourneyList proto the KMP client consumes,
 * mirroring [JourneyListMapper]'s conventions for the NSW path.
 *
 * Times: the router works in seconds since midnight of the query date
 * (Melbourne). Instants are derived via elapsed seconds from local midnight,
 * which drifts one hour across a DST changeover mid-journey — known gap,
 * see docs/reference/VIC_ROUTER_DESIGN.md §11.
 *
 * `now` is a parameter (for the "in X mins" card text) — callers inject the
 * clock, this mapper never reads one.
 */
object VicJourneyListMapper {

    private val melbourne = ZoneId.of("Australia/Melbourne")
    private val timeFormatter = DateTimeFormatter.ofPattern("h:mma").withZone(melbourne)

    fun toProto(model: RouterModel, journeys: List<Journey>, date: LocalDate, now: Instant): JourneyList =
        JourneyList(journeys = journeys.mapNotNull { mapJourney(model, it, date, now) })

    private fun mapJourney(model: RouterModel, journey: Journey, date: LocalDate, now: Instant): JourneyCardInfo? {
        val transitLegs = journey.legs.filterIsInstance<JourneyLeg.Transit>()
        // Walk-only journeys have no boarding to hang a card on; skip them.
        if (transitLegs.isEmpty()) return null

        val base = date.atStartOfDay(melbourne)
        fun instantOf(sec: Int): Instant = base.plusSeconds(sec.toLong()).toInstant()

        val originInstant = instantOf(transitLegs.first().boardDepSec)
        val arrivalInstant = instantOf(transitLegs.last().alightArrSec)

        val totalWalkSeconds = journey.legs
            .filterIsInstance<JourneyLeg.Walk>()
            .sumOf { it.durationSec.toLong() }

        return JourneyCardInfo(
            time_text = timeUntilText(now, originInstant),
            origin_time = formatTime(originInstant),
            origin_utc_date_time = originInstant.toString(),
            destination_time = formatTime(arrivalInstant),
            destination_utc_date_time = arrivalInstant.toString(),
            travel_time = formatDuration(Duration.between(originInstant, arrivalInstant).seconds),
            total_walk_time = totalWalkSeconds.takeIf { it > 0 }?.let { formatDuration(it) },
            transport_mode_lines = transitLegs.map { leg ->
                TransportModeLine(
                    line_name = lineName(model, leg.route),
                    transport_mode_type = productClass(model.routeTypes[leg.route]),
                )
            },
            legs = journey.legs.map { mapLeg(model, it, ::instantOf) },
            total_unique_service_alerts = 0,
        )
    }

    private fun mapLeg(model: RouterModel, leg: JourneyLeg, instantOf: (Int) -> Instant): Leg = when (leg) {
        is JourneyLeg.Walk -> Leg(
            walking_leg = WalkingLeg(duration = formatDuration(leg.durationSec.toLong()))
        )
        is JourneyLeg.Transit -> {
            val pc = productClass(model.routeTypes[leg.route])
            val lastIdx = leg.stops.lastIndex
            Leg(
                transport_leg = TransportLeg(
                    transport_mode_line = TransportModeLine(
                        line_name = lineName(model, leg.route),
                        transport_mode_type = pc,
                    ),
                    display_text = leg.headsign?.takeIf { it.isNotBlank() }?.let { "towards $it" }
                        ?: model.routeLongNames[leg.route].takeIf { it.isNotBlank() },
                    total_duration = formatDuration((leg.alightArrSec - leg.boardDepSec).toLong()),
                    stops = leg.stops.mapIndexed { i, call ->
                        val instant = instantOf(if (i == lastIdx) call.arrSec else call.depSec)
                        Stop(
                            name = model.stopNames[call.stop],
                            time = formatTime(instant),
                            coord = Coord(lat = model.stopLats[call.stop], lon = model.stopLons[call.stop]),
                            stop_id = "VIC:${model.stopIds[call.stop]}",
                            utc_time = instant.toString(),
                        )
                    },
                    trip_id = leg.tripId,
                    transportation_id = model.routeIds[leg.route],
                    // realtime_trip_id arrives with the GTFS-RT feed work.
                )
            )
        }
    }

    private fun lineName(model: RouterModel, route: Int): String =
        model.routeShortNames[route].ifBlank { model.routeLongNames[route] }

    /**
     * GTFS route_type (basic + extended) → KRAIL product class as used by the
     * KMP client (1 train, 2 metro, 4 light rail, 5 bus, 7 coach, 9 ferry).
     * VIC uses 400 metro rail, 0 tram, 3+701 bus, 102/106 V/Line rail.
     */
    private fun productClass(routeType: Int): Int = when (routeType) {
        0, in 900..999 -> 4
        1 -> 2
        2, in 100..199, in 400..499 -> 1
        3, in 700..799, 800 -> 5
        4, in 1000..1099, 1200 -> 9
        in 200..299 -> 7
        else -> 5
    }

    private fun timeUntilText(now: Instant, originTime: Instant): String {
        val minutesUntil = Duration.between(now, originTime).toMinutes()
        return when {
            minutesUntil <= 0 -> "now"
            minutesUntil == 1L -> "in 1 min"
            minutesUntil < 60 -> "in $minutesUntil mins"
            else -> {
                val hours = minutesUntil / 60
                val mins = minutesUntil % 60
                if (mins == 0L) "in $hours hr" else "in $hours hr $mins min"
            }
        }
    }

    private fun formatTime(instant: Instant): String = timeFormatter.format(instant).lowercase()

    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        return when {
            minutes == 0L -> "< 1 min"
            minutes == 1L -> "1 min"
            minutes < 60 -> "$minutes mins"
            else -> {
                val hours = minutes / 60
                val mins = minutes % 60
                if (mins == 0L) "$hours hr" else "$hours hr $mins min"
            }
        }
    }
}
