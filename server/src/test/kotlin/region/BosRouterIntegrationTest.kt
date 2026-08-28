package app.krail.bff.region

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Boston (MBTA) golden tests — gate on BOS_GTFS_DIR. Feed quirks this pins:
 * the pathways pioneer ships pathways/levels/facilities and thousands of
 * location_type 2/3 entries the parser must classify as non-routable;
 * transfers.txt mixes usable 0/1/2 rows with 4.6k trip-qualified type-4
 * continuations; ~90k no-pickup and ~90k no-drop-off calls; after-midnight
 * service past 24:00 on ~105k rows.
 */
class BosRouterIntegrationTest : RegionGoldenTest(RegionRegistry.bos) {

    @Test
    fun `model covers subway light rail commuter rail bus and ferry`() = ifData { model, _, _ ->
        val types = model.routeTypes.toSet()
        assertTrue(1 in types, "no subway routes")
        assertTrue(0 in types, "no light-rail (Green/Mattapan) routes")
        assertTrue(2 in types, "no commuter-rail routes")
        assertTrue(3 in types, "no bus routes")
        assertTrue(4 in types, "no ferry routes")
        assertTrue(model.stopCount > 5_000, "suspiciously few stops: ${model.stopCount}")
    }

    @Test
    fun `park street to harvard on the red line`() = ifData { model, router, date ->
        // Published Red Line leg is ~9-11 min.
        assertCanonical(
            model, router, date,
            fromId = "place-pktrm", toId = "place-harsq",
            durationRange = 5..25,
            expectedRouteTypes = setOf(1),
            label = "Park Street -> Harvard",
        )
    }

    @Test
    fun `park street to government center on the green line`() = ifData { model, router, date ->
        assertCanonical(
            model, router, date,
            fromId = "place-pktrm", toId = "place-gover",
            durationRange = 1..15,
            expectedRouteTypes = setOf(0, 1),
            label = "Park Street -> Government Center",
        )
    }

    @Test
    fun `stations resolve to platforms`() = ifData { model, _, _ ->
        assertTrue(model.stopsForId("place-pktrm").size >= 2, "Park Street should have multiple platforms")
    }
}
