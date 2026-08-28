package app.krail.bff.region

import app.krail.bff.mapper.RegionJourneyListMapper
import app.krail.bff.proto.JourneyList
import app.krail.bff.router.RaptorRouter
import app.krail.bff.router.RouterModel
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Plans one region's journeys against an in-memory [RouterModel] and shapes
 * them into the JourneyList proto. No upstream API involved — every
 * [RegionRegistry] region publishes GTFS but no journey-planner API, so the
 * BFF computes routes itself. One instance per loaded region; the region
 * contributes only its timezone and public stop-id prefix (repo invariant:
 * the router core and this service are city-agnostic).
 *
 * The clock is injected (repo invariant): it only fills defaults when the
 * caller omits date/time, and feeds the card's "in X mins" text.
 */
class RegionTripService(
    private val model: RouterModel,
    private val zone: ZoneId,
    idPrefix: String,
    private val clock: () -> Instant = Instant::now,
) {
    constructor(
        model: RouterModel,
        spec: RegionSpec,
        clock: () -> Instant = Instant::now,
    ) : this(model, spec.zone, spec.idPrefix, clock)

    private val router = RaptorRouter(model)
    private val prefix = idPrefix
    private val mapper = RegionJourneyListMapper(zone, idPrefix)

    /**
     * Stop names folded for search: diacritics stripped, lowercased. Built on
     * first search (Router Lab is dev tooling; production requests never pay
     * for it). Folding makes "Otahuhu" match "Ōtāhuhu" and "Andel" match
     * "Anděl" — macrons, umlauts and háčky all reduce the same way.
     */
    private val foldedNames: Array<String> by lazy {
        Array(model.stopNames.size) { foldForSearch(model.stopNames[it]) }
    }

    fun knowsStop(id: String): Boolean = model.stopsForId(id).isNotEmpty()

    /**
     * Diacritic-insensitive substring search over stop names, for the Router
     * Lab dev dashboard's autocomplete. Linear scan — dev tooling, ≤~50k
     * names per region.
     */
    fun searchStops(query: String, limit: Int = 20): List<RegionStopHit> {
        val q = foldForSearch(query)
        if (q.isEmpty()) return emptyList()
        val hits = ArrayList<RegionStopHit>(limit)
        val names = foldedNames
        for (i in names.indices) {
            if (names[i].contains(q)) {
                hits.add(
                    RegionStopHit(
                        id = "$prefix:${model.stopIds[i]}",
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
     * to "now" in the region's timezone.
     */
    fun planProto(originId: String, destinationId: String, date: LocalDate?, time: String?): JourneyList {
        val now = clock()
        val nowLocal = now.atZone(zone)
        val queryDate = date ?: nowLocal.toLocalDate()
        val depSec = time?.let { it.take(2).toInt() * 3600 + it.drop(2).toInt() * 60 }
            ?: nowLocal.toLocalTime().toSecondOfDay()
        val journeys = router.plan(originId, destinationId, queryDate, depSec)
            .sortedBy { it.arrSec } // fastest arrival first — card order for the client
        return mapper.toProto(model, journeys, queryDate, now)
    }

    companion object {
        private val COMBINING_MARKS = Regex("\\p{Mn}+")

        /** Unicode NFD fold: decompose, strip combining marks, lowercase. */
        fun foldForSearch(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD)
                .replace(COMBINING_MARKS, "")
                .lowercase()
    }
}

data class RegionStopHit(val id: String, val name: String, val lat: Double, val lon: Double)
