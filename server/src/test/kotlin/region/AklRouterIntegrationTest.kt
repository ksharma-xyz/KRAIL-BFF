package app.krail.bff.region

import app.krail.bff.router.STOP_TIME_NO_PICKUP
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.experimental.and

/**
 * Auckland (AT) golden tests — gate on AKL_GTFS_DIR. Feed quirks this pins:
 * station/stop ids carry a per-drop hash suffix (Britomart is
 * `133-08da14b5` today, something else next drop), so the rail pair
 * resolves stations by name; pickup restrictions are heavily used
 * (~35k no-pickup calls); frequencies.txt ships but is empty.
 */
class AklRouterIntegrationTest : RegionGoldenTest(RegionRegistry.akl) {

    @Test
    fun `model covers rail bus and ferry`() = ifData { model, _, _ ->
        val types = model.routeTypes.toSet()
        assertTrue(2 in types, "no rail routes")
        assertTrue(3 in types, "no bus routes")
        assertTrue(4 in types, "no ferry routes")
        assertTrue(model.stopCount > 5_000, "suspiciously few stops: ${model.stopCount}")
    }

    @Test
    fun `britomart to newmarket by name survives id churn`() = ifData { model, router, date ->
        // Waitematā (Britomart) platforms are named "Waitemata Train Station 1..4".
        val from = stopsByNamePrefix(model, "Waitemata Train Station")
        val to = stopsByNamePrefix(model, "Newmarket Train Station")
        assertTrue(from.isNotEmpty(), "no Waitemata (Britomart) platforms found by name")
        assertTrue(to.isNotEmpty(), "no Newmarket platforms found by name")
        val journeys = router.planStops(from, to, date, sec(8, 30))
        assertTrue(journeys.isNotEmpty(), "no journey Britomart -> Newmarket")
        val best = journeys.minByOrNull { it.arrSec }!!
        // Published Southern/Western line leg is ~7-10 min.
        assertTrue(best.durationMin() in 3..30, "implausible duration ${best.durationMin()}min")
        assertTrue(
            journeys.any { j -> transitLegs(j).any { model.routeTypes[it.route] == 2 } },
            "expected a rail leg",
        )
    }

    @Test
    fun `pickup restrictions made it into the model`() = ifData { model, _, _ ->
        val restricted = model.stopTimeFlags.count { it and STOP_TIME_NO_PICKUP.toByte() != 0.toByte() }
        assertTrue(restricted > 1_000, "AT publishes ~35k no-pickup calls; found $restricted")
    }
}
