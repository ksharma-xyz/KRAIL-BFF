package app.krail.bff.vic

import app.krail.bff.router.Journey
import app.krail.bff.router.JourneyLeg
import app.krail.bff.router.RaptorRouter
import app.krail.bff.router.RouterModel
import java.nio.file.Files
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Golden tests against the real VIC GTFS dataset. Run only when VIC_GTFS_DIR
 * points at a directory of extracted folders (<n>/google_transit.zip);
 * skipped silently otherwise so `./gradlew :server:test` stays green in CI.
 *
 * The dataset shifts weekly, so assertions are structural (journey exists,
 * sane duration and legs) — never exact times. The query date comes from the
 * dataset's own calendar span, never the wall clock.
 */
class VicRouterIntegrationTest {

    private companion object {
        val gtfsDir: Path? = System.getenv("VIC_GTFS_DIR")
            ?.let { Path.of(it) }
            ?.takeIf { Files.isDirectory(it) }

        // Built once for the whole class; ~10M stop-time rows.
        val model: RouterModel by lazy { VicGtfsIngest.buildModel(gtfsDir!!) }
        val router: RaptorRouter by lazy { RaptorRouter(model) }

        val queryDate: LocalDate by lazy {
            val start = model.meta.calendarStart
            var d = LocalDate.of(start / 10000, start / 100 % 100, start % 100)
            while (d.dayOfWeek != DayOfWeek.TUESDAY) d = d.plusDays(1)
            d
        }
    }

    private fun ifData(block: () -> Unit) {
        if (gtfsDir == null) {
            println("VIC_GTFS_DIR not set — skipping VIC integration test")
            return
        }
        block()
    }

    private fun sec(h: Int, m: Int) = h * 3600 + m * 60

    private fun Journey.durationMin() = (arrSec - depSec) / 60

    private fun transitLegs(j: Journey) = j.legs.filterIsInstance<JourneyLeg.Transit>()

    @Test
    fun `flinders street to melbourne central`() = ifData {
        val journeys = router.plan("vic:rail:FSS", "vic:rail:MCE", queryDate, sec(8, 30))
        assertTrue(journeys.isNotEmpty(), "no journey FSS -> MCE")
        val best = journeys.minByOrNull { it.arrSec }!!
        assertTrue(best.durationMin() in 1..30, "implausible duration ${best.durationMin()}min")
        assertTrue(transitLegs(best).isNotEmpty())
    }

    @Test
    fun `city loop pair flagstaff to parliament`() = ifData {
        val journeys = router.plan("vic:rail:FGS", "vic:rail:PAR", queryDate, sec(8, 30))
        assertTrue(journeys.isNotEmpty(), "no journey FGS -> PAR")
        val best = journeys.minByOrNull { it.arrSec }!!
        assertTrue(best.durationMin() in 1..25, "implausible duration ${best.durationMin()}min")
    }

    @Test
    fun `tram-only pair on route 96`() = ifData {
        // Find route 96's longest tram pattern at runtime — stop ids shift
        // between weekly dataset drops.
        val routeIdx = model.routeIds.indices.firstOrNull {
            model.routeShortNames[it] == "96" && model.routeTypes[it] == 0
        }
        assertTrue(routeIdx != null, "tram route 96 not found in dataset")
        var bestPattern = -1
        var bestLen = 0
        for (p in 0 until model.patternCount) {
            if (model.patternRoute[p] != routeIdx) continue
            val len = model.patternStopCount(p)
            if (len > bestLen) {
                bestLen = len
                bestPattern = p
            }
        }
        assertTrue(bestLen >= 10, "route 96 pattern too short: $bestLen")
        val from = model.patternStopAt(bestPattern, 2)
        val to = model.patternStopAt(bestPattern, bestLen - 3)
        val journeys = router.planStops(intArrayOf(from), intArrayOf(to), queryDate, sec(10, 0))
        assertTrue(journeys.isNotEmpty(), "no journey along route 96")
        val best = journeys.minByOrNull { it.arrSec }!!
        assertTrue(best.durationMin() in 5..90, "implausible duration ${best.durationMin()}min")
        assertTrue(
            journeys.any { j -> transitLegs(j).any { model.routeTypes[it.route] == 0 } },
            "expected a tram leg",
        )
    }

    @Test
    fun `cross-mode bus to train into the cbd`() = ifData {
        val busTypes = setOf(3, 700, 701, 702, 704, 712, 713)
        fun isBusLeg(leg: JourneyLeg.Transit) = model.routeTypes[leg.route] in busTypes

        val fssStops = model.stopsForId("vic:rail:FSS")
        assertTrue(fssStops.isNotEmpty())
        val fssLat = model.stopLats[fssStops[0]]
        val fssLon = model.stopLons[fssStops[0]]
        // A suburban bus stop 12-25km out: reaching Flinders St from there
        // means changing onto rail or tram at some point.
        var crossMode: Journey? = null
        var tried = 0
        for (p in 0 until model.patternCount) {
            if (model.routeTypes[model.patternRoute[p]] !in busTypes) continue
            if (model.patternStopCount(p) < 8) continue
            val s = model.patternStopAt(p, model.patternStopCount(p) / 2)
            val km = app.krail.bff.router.haversineMeters(
                model.stopLats[s], model.stopLons[s], fssLat, fssLon
            ) / 1000.0
            if (km < 12 || km > 25) continue
            tried++
            val journeys = router.planStops(intArrayOf(s), fssStops, queryDate, sec(8, 0))
            crossMode = journeys.firstOrNull { j ->
                val legs = transitLegs(j)
                legs.size >= 2 && legs.any(::isBusLeg) && legs.any { !isBusLeg(it) }
            }
            if (crossMode != null || tried >= 25) break
        }
        assertTrue(tried > 0, "no suburban bus stop candidates found")
        val journey = crossMode
        assertTrue(journey != null, "no bus + train/tram journey into the CBD found in $tried tries")
        assertTrue(journey.durationMin() in 15..180, "implausible duration ${journey.durationMin()}min")
    }
}
