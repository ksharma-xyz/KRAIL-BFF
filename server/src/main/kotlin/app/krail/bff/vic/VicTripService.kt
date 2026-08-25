package app.krail.bff.vic

import app.krail.bff.mapper.VicJourneyListMapper
import app.krail.bff.proto.JourneyList
import app.krail.bff.router.RaptorRouter
import app.krail.bff.router.RouterModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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
     * Case-insensitive substring search over stop names, for the Router Lab
     * dev dashboard's autocomplete. Linear scan — dev tooling, ~25k names.
     */
    fun searchStops(query: String, limit: Int = 20): List<StopHit> {
        val q = query.lowercase()
        val hits = ArrayList<StopHit>(limit)
        for (i in model.stopIds.indices) {
            if (model.stopNames[i].lowercase().contains(q)) {
                hits.add(
                    StopHit(
                        id = "VIC:${model.stopIds[i]}",
                        name = model.stopNames[i],
                        lat = model.stopLats[i],
                        lon = model.stopLons[i],
                    )
                )
                if (hits.size >= limit) break
            }
        }
        return hits
    }

    /**
     * [date] is pre-parsed by the route (invalid calendar dates are a 400
     * there, never an exception here); [time] is validated HHmm. Both default
     * to "now" in Melbourne.
     */
    fun planProto(originId: String, destinationId: String, date: LocalDate?, time: String?): JourneyList {
        val now = clock()
        val nowMelbourne = now.atZone(melbourne)
        val queryDate = date ?: nowMelbourne.toLocalDate()
        val depSec = time?.let { it.take(2).toInt() * 3600 + it.drop(2).toInt() * 60 }
            ?: nowMelbourne.toLocalTime().toSecondOfDay()
        val journeys = router.plan(originId, destinationId, queryDate, depSec)
            .sortedBy { it.arrSec } // fastest arrival first — card order for the client
        return VicJourneyListMapper.toProto(model, journeys, queryDate, now)
    }
}

data class StopHit(val id: String, val name: String, val lat: Double, val lon: Double)
