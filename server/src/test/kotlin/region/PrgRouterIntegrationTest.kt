package app.krail.bff.region

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Prague (PID) golden tests — gate on PRG_GTFS_DIR. Feed quirks this pins:
 * PID custom files (route_sub_agencies, route_stops, vehicle_*) the parser
 * must ignore; a calendar that opens months before most services run (the
 * query-date scan must skip dead weeks); 14.8k trip-qualified type-1
 * transfers (skipped) beside 50 usable type-2 rows; location_type 4
 * boarding areas; Czech diacritics everywhere (15k+ non-ASCII names).
 */
class PrgRouterIntegrationTest : RegionGoldenTest(RegionRegistry.prg) {

    @Test
    fun `model covers metro tram rail bus and ferry`() = ifData { model, _, _ ->
        val types = model.routeTypes.toSet()
        assertTrue(1 in types, "no metro routes")
        assertTrue(0 in types, "no tram routes")
        assertTrue(2 in types, "no rail routes")
        assertTrue(3 in types, "no bus routes")
        assertTrue(11 in types, "no trolleybus (11) routes")
        assertTrue(model.stopCount > 10_000, "suspiciously few stops: ${model.stopCount}")
    }

    @Test
    fun `muzeum to andel on the metro`() = ifData { model, router, date ->
        // Line C/A one hop + line B, or tram — either way well under 25 min.
        assertCanonical(
            model, router, date,
            fromId = "U400S1", toId = "U1040S1",
            durationRange = 3..25,
            expectedRouteTypes = setOf(0, 1),
            label = "Muzeum -> Andel",
        )
    }

    @Test
    fun `muzeum to hlavni nadrazi`() = ifData { model, router, date ->
        // One stop on line C — the scheduled hop can round to 0 minutes.
        assertCanonical(
            model, router, date,
            fromId = "U400S1", toId = "U142S1",
            durationRange = 0..20,
            label = "Muzeum -> Hlavni nadrazi",
        )
    }

    @Test
    fun `czech diacritics survive the pipeline`() = ifData { model, _, _ ->
        val andelPlatforms = model.stopsForId("U1040S1")
        assertTrue(andelPlatforms.isNotEmpty(), "Andel station resolves to no platforms")
        assertTrue(
            andelPlatforms.all { model.stopNames[it] == "Anděl" },
            "expected 'Anděl' platform names — UTF-8 path broken?",
        )
    }
}
