package app.krail.bff.region

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Wellington (Metlink) golden tests — gate on WLG_GTFS_DIR. Feed quirks this
 * pins: extended route_type 712 (school buses) alongside basic 3; the
 * Wellington Cable Car is basic type 5 (cable tram); transfers.txt is
 * entirely trip-qualified type-2 rows (all skipped — footpaths are
 * generated); station ids are stable four-letter codes (WELL, PORI).
 */
class WlgRouterIntegrationTest : RegionGoldenTest(RegionRegistry.wlg) {

    @Test
    fun `model covers rail bus school-bus ferry and cable car`() = ifData { model, _, _ ->
        val types = model.routeTypes.toSet()
        assertTrue(2 in types, "no rail routes")
        assertTrue(3 in types, "no bus routes")
        assertTrue(712 in types, "no school-bus (712) routes")
        assertTrue(4 in types, "no ferry routes")
        assertTrue(5 in types, "no cable-car (5) route")
        assertTrue(model.stopCount > 2_000, "suspiciously few stops: ${model.stopCount}")
    }

    @Test
    fun `wellington to porirua on the kapiti line`() = ifData { model, router, date ->
        // Published KPL timetable: ~21-24 min.
        assertCanonical(
            model, router, date,
            fromId = "WELL", toId = "PORI",
            durationRange = 10..45,
            expectedRouteTypes = setOf(2),
            label = "Wellington -> Porirua",
        )
    }

    @Test
    fun `wellington to taita on the hutt valley line`() = ifData { model, router, date ->
        assertCanonical(
            model, router, date,
            fromId = "WELL", toId = "TAIT",
            durationRange = 15..50,
            expectedRouteTypes = setOf(2),
            label = "Wellington -> Taita",
        )
    }
}
