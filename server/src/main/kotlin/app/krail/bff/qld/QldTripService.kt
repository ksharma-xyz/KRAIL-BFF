package app.krail.bff.qld

import app.krail.bff.mapper.RegionJourneyListMapper
import app.krail.bff.proto.JourneyList
import app.krail.bff.router.RaptorRouter
import app.krail.bff.router.RouterModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Plans SEQ (Brisbane and surrounds) journeys against an in-memory
 * [RouterModel] and shapes them into the JourneyList proto. No upstream API
 * involved — Translink publishes GTFS but no journey-planner API, so the BFF
 * computes routes itself, exactly like VIC.
 *
 * Queensland has no daylight saving, so Australia/Brisbane is a fixed +10:00
 * offset year-round — the DST-drift gap documented for VIC does not apply.
 *
 * The clock is injected (repo invariant): it only fills defaults when the
 * caller omits date/time, and feeds the card's "in X mins" text.
 */
class QldTripService(
    private val model: RouterModel,
    private val clock: () -> Instant = Instant::now,
) {
    private val router = RaptorRouter(model)
    private val brisbane = ZoneId.of("Australia/Brisbane")
    private val mapper = RegionJourneyListMapper(brisbane, "QLD")

    fun knowsStop(id: String): Boolean = model.stopsForId(id).isNotEmpty()

    /**
     * Case-insensitive substring search over stop names, for the Router Lab
     * dev dashboard's autocomplete. Linear scan — dev tooling, ~13k names.
     */
    fun searchStops(query: String, limit: Int = 20): List<QldStopHit> {
        val q = query.lowercase()
        val hits = ArrayList<QldStopHit>(limit)
        for (i in model.stopIds.indices) {
            if (model.stopNames[i].lowercase().contains(q)) {
                hits.add(
                    QldStopHit(
                        id = "QLD:${model.stopIds[i]}",
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
     * to "now" in Brisbane.
     */
    fun planProto(originId: String, destinationId: String, date: LocalDate?, time: String?): JourneyList {
        val now = clock()
        val nowBrisbane = now.atZone(brisbane)
        val queryDate = date ?: nowBrisbane.toLocalDate()
        val depSec = time?.let { it.take(2).toInt() * 3600 + it.drop(2).toInt() * 60 }
            ?: nowBrisbane.toLocalTime().toSecondOfDay()
        val journeys = router.plan(originId, destinationId, queryDate, depSec)
            .sortedBy { it.arrSec } // fastest arrival first — card order for the client
        return mapper.toProto(model, journeys, queryDate, now)
    }
}

data class QldStopHit(val id: String, val name: String, val lat: Double, val lon: Double)
