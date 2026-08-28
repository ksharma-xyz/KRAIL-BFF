package app.krail.bff.region

import app.krail.bff.router.Journey
import app.krail.bff.router.JourneyLeg
import app.krail.bff.router.RaptorRouter
import app.krail.bff.router.RouterModel
import java.nio.file.Files
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.assertTrue

/**
 * Shared machinery for golden tests against a region's real GTFS dataset.
 * Each region's test class runs only when `{CODE}_GTFS_DIR` points at the
 * feed zip (or a directory containing it); skipped silently otherwise so
 * `./gradlew :server:test` stays green in CI.
 *
 * Datasets shift weekly, so assertions are structural (journey exists, sane
 * duration and legs) — never exact times. The query date comes from the
 * dataset's own calendar, never the wall clock: the first Tuesday with a
 * meaningful share of active services (PID's calendar opens months before
 * most services begin, so plain calendar-start Tuesdays can be dead days).
 */
abstract class RegionGoldenTest(protected val spec: RegionSpec) {

    /** One model per region per JVM — the builds are the expensive part. */
    private object Models {
        private val cache = HashMap<String, RouterModel?>()
        fun modelFor(spec: RegionSpec): RouterModel? = synchronized(cache) {
            cache.getOrPut(spec.code) {
                System.getenv(spec.gtfsDirEnvVar)
                    ?.let { Path.of(it) }
                    ?.takeIf { Files.exists(it) }
                    ?.let { RegionGtfsIngest.buildModel(spec, it) }
            }
        }
    }

    protected fun ifData(block: (RouterModel, RaptorRouter, LocalDate) -> Unit) {
        val model = Models.modelFor(spec)
        if (model == null) {
            println("${spec.gtfsDirEnvVar} not set — skipping ${spec.code} golden test")
            return
        }
        block(model, RaptorRouter(model), queryDate(model))
    }

    private fun queryDate(model: RouterModel): LocalDate {
        val start = model.meta.calendarStart
        var first = LocalDate.of(start / 10000, start / 100 % 100, start % 100)
        while (first.dayOfWeek != DayOfWeek.TUESDAY) first = first.plusDays(1)
        val end = model.meta.calendarEnd
        val endDate = LocalDate.of(end / 10000, end / 100 % 100, end % 100)
        // Survey every Tuesday in the span by *active trip count* (active
        // services alone mislead: PID's year-long calendar keeps hundreds of
        // regional services nominally active months before the drop's actual
        // metro/tram trips run), then take the first Tuesday within 90% of
        // peak — i.e. the first fully-timetabled day of the drop.
        var d = first
        val activity = ArrayList<Pair<LocalDate, Int>>()
        while (d <= endDate) {
            val active = model.activeServices(d)
            var trips = 0
            for (svc in model.tripServices) if (active.get(svc)) trips++
            activity.add(d to trips)
            d = d.plusWeeks(1)
        }
        if (activity.isEmpty()) return first
        val peak = activity.maxOf { it.second }
        return activity.first { it.second * 10 >= peak * 9 }.first
    }

    protected fun sec(h: Int, m: Int) = h * 3600 + m * 60

    protected fun Journey.durationMin() = (arrSec - depSec) / 60

    protected fun transitLegs(j: Journey) = j.legs.filterIsInstance<JourneyLeg.Transit>()

    /** Stops whose display name starts with [prefix] — survives id-suffix churn (AKL). */
    protected fun stopsByNamePrefix(model: RouterModel, prefix: String): IntArray {
        val out = ArrayList<Int>()
        for (i in model.stopNames.indices) {
            if (model.stopNames[i].startsWith(prefix)) out.add(i)
        }
        return out.toIntArray()
    }

    /** Plans [fromId] -> [toId] at 08:30 and applies the standard structural checks. */
    protected fun assertCanonical(
        model: RouterModel,
        router: RaptorRouter,
        date: LocalDate,
        fromId: String,
        toId: String,
        durationRange: IntRange,
        expectedRouteTypes: Set<Int>? = null,
        label: String = "$fromId -> $toId",
    ) {
        val journeys = router.plan(fromId, toId, date, sec(8, 30))
        assertTrue(journeys.isNotEmpty(), "no journey $label on $date")
        val best = journeys.minByOrNull { it.arrSec }!!
        assertTrue(
            best.durationMin() in durationRange,
            "implausible duration ${best.durationMin()}min for $label (expected $durationRange)",
        )
        assertTrue(transitLegs(best).isNotEmpty(), "walk-only best journey for $label")
        if (expectedRouteTypes != null) {
            assertTrue(
                journeys.any { j -> transitLegs(j).any { model.routeTypes[it.route] in expectedRouteTypes } },
                "no leg with route_type in $expectedRouteTypes for $label",
            )
        }
    }
}
