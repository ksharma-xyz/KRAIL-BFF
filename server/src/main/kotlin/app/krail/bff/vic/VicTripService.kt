package app.krail.bff.vic

import app.krail.bff.mapper.VicJourneyListMapper
import app.krail.bff.proto.JourneyList
import app.krail.bff.router.RaptorRouter
import app.krail.bff.router.RouterModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Plans VIC journeys against an in-memory [RouterModel] and shapes them into
 * the JourneyList proto. No upstream API involved — Victoria has no journey
 * planner; the BFF computes routes itself.
 *
 * The clock is injected (repo invariant): it only fills defaults when the
 * caller omits date/time, and feeds the card's "in X mins" text.
 */
class VicTripService(
    private val model: RouterModel,
    private val clock: () -> Instant = Instant::now,
) {
    private val router = RaptorRouter(model)
    private val melbourne = ZoneId.of("Australia/Melbourne")

    fun knowsStop(id: String): Boolean = model.stopsForId(id).isNotEmpty()

    /**
     * [date] is YYYYMMDD, [time] is HHmm (already validated by the route);
     * both default to "now" in Melbourne.
     */
    fun planProto(originId: String, destinationId: String, date: String?, time: String?): JourneyList {
        val now = clock()
        val nowMelbourne = now.atZone(melbourne)
        val queryDate = date?.let { LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE) }
            ?: nowMelbourne.toLocalDate()
        val depSec = time?.let { it.take(2).toInt() * 3600 + it.drop(2).toInt() * 60 }
            ?: nowMelbourne.toLocalTime().toSecondOfDay()
        val journeys = router.plan(originId, destinationId, queryDate, depSec)
        return VicJourneyListMapper.toProto(model, journeys, queryDate, now)
    }
}
