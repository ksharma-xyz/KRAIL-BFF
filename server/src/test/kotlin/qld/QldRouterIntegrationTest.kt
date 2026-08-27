package app.krail.bff.qld

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
 * Golden tests against the real SEQ GTFS dataset. Run only when QLD_GTFS_DIR
 * points at SEQ_GTFS.zip or a directory containing it; skipped silently
 * otherwise so `./gradlew :server:test` stays green in CI.
 *
 * The dataset shifts weekly, so assertions are structural (journey exists,
 * sane duration and legs) — never exact times. The query date comes from the
 * dataset's own calendar span, never the wall clock.
 */
class QldRouterIntegrationTest {

    private companion object {
        val gtfsPath: Path? = System.getenv("QLD_GTFS_DIR")
            ?.let { Path.of(it) }
            ?.takeIf { Files.exists(it) }

        // Built once for the whole class; ~3.2M stop-time rows.
        val model: RouterModel by lazy { QldGtfsIngest.buildModel(gtfsPath!!) }
        val router: RaptorRouter by lazy { RaptorRouter(model) }

        val queryDate: LocalDate by lazy {
            val start = model.meta.calendarStart
            var d = LocalDate.of(start / 10000, start / 100 % 100, start % 100)
            while (d.dayOfWeek != DayOfWeek.TUESDAY) d = d.plusDays(1)
            d
        }
    }

    private fun ifData(block: () -> Unit) {
        if (gtfsPath == null) {
            println("QLD_GTFS_DIR not set — skipping QLD integration test")
            return
        }
        block()
    }

    private fun sec(h: Int, m: Int) = h * 3600 + m * 60

    private fun Journey.durationMin() = (arrSec - depSec) / 60

    private fun transitLegs(j: Journey) = j.legs.filterIsInstance<JourneyLeg.Transit>()

    @Test
    fun `model covers all four seq modes`() = ifData {
        // Single flat feed: bus, Citytrain, G:link tram, ferry.
        val types = model.routeTypes.toSet()
        assertTrue(2 in types, "no rail routes in model")
        assertTrue(3 in types, "no bus routes in model")
        assertTrue(0 in types, "no tram routes in model")
        assertTrue(4 in types, "no ferry routes in model")
        assertTrue(model.stopCount > 10_000, "suspiciously few stops: ${model.stopCount}")
        assertTrue(model.tripCount > 50_000, "suspiciously few trips: ${model.tripCount}")
    }

    @Test
    fun `central to roma street`() = ifData {
        val journeys = router.plan("place_censta", "place_romsta", queryDate, sec(8, 30))
        assertTrue(journeys.isNotEmpty(), "no journey Central -> Roma Street")
        val best = journeys.minByOrNull { it.arrSec }!!
        assertTrue(best.durationMin() in 1..20, "implausible duration ${best.durationMin()}min")
        assertTrue(transitLegs(best).isNotEmpty())
    }

    @Test
    fun `citycat ferry pair west end to new farm park`() = ifData {
        val journeys = router.plan("place_westft", "place_newfft", queryDate, sec(9, 0))
        assertTrue(journeys.isNotEmpty(), "no journey West End -> New Farm Park")
        assertTrue(
            journeys.any { j -> transitLegs(j).any { model.routeTypes[it.route] == 4 } },
            "expected a ferry leg on a river pair",
        )
    }

    @Test
    fun `glink tram pair broadbeach south to cavill avenue`() = ifData {
        val journeys = router.plan("place_brsstn", "place_cavsta", queryDate, sec(10, 0))
        assertTrue(journeys.isNotEmpty(), "no journey Broadbeach South -> Cavill Ave")
        val best = journeys.minByOrNull { it.arrSec }!!
        assertTrue(best.durationMin() in 3..40, "implausible duration ${best.durationMin()}min")
        assertTrue(
            journeys.any { j -> transitLegs(j).any { model.routeTypes[it.route] == 0 } },
            "expected a G:link tram leg",
        )
    }

    @Test
    fun `cross-mode bus and rail from uq to eagle junction`() = ifData {
        // UQ Chancellors Place is bus-only; Eagle Junction is on the rail
        // north side — a sane answer crosses modes somewhere in the CBD.
        val journeys = router.plan("place_intuq", "place_egjsta", queryDate, sec(8, 0))
        assertTrue(journeys.isNotEmpty(), "no journey UQ -> Eagle Junction")
        val best = journeys.minByOrNull { it.arrSec }!!
        assertTrue(best.durationMin() in 15..120, "implausible duration ${best.durationMin()}min")
        assertTrue(
            journeys.any { j ->
                val legs = transitLegs(j)
                legs.any { model.routeTypes[it.route] == 3 } && legs.any { model.routeTypes[it.route] == 2 }
            },
            "expected a bus + rail journey",
        )
    }

    @Test
    fun `after-midnight nightlink trips are boardable next calendar day`() = ifData {
        // SEQ times run to 30:00+ — a 00:30 query must still see yesterday's
        // service day (invariant I3). Structural: some journey exists from
        // Central at 00:30 on the day after a Friday night (Sat 00:30).
        var friday = queryDate
        while (friday.dayOfWeek != DayOfWeek.FRIDAY) friday = friday.plusDays(1)
        val saturdayNight = friday.plusDays(1)
        val journeys = router.plan("place_censta", "place_romsta", saturdayNight, sec(0, 30))
        assertTrue(journeys.isNotEmpty(), "no journey at 00:30 Sat — after-midnight scan broken?")
    }
}
