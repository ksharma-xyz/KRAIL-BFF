package app.krail.bff.region

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Berlin/Brandenburg (VBB) golden tests — gate on BER_GTFS_DIR. Feed quirks
 * this pins: everything is extended route types (700 bus, 100/109 rail,
 * 400 U-Bahn, 900 tram, 1000 ferry — barely any basic codes); DHID stop ids
 * with colons (`de:11000:900003201`); ~48k usable curated transfers (the
 * largest curated set of any region — I5 precedence matters here);
 * frequencies.txt ships but is empty in the current drop; umlauts
 * throughout stop names.
 */
class BerRouterIntegrationTest : RegionGoldenTest(RegionRegistry.ber) {

    @Test
    fun `model covers s-bahn u-bahn tram bus and ferry (extended types)`() = ifData { model, _, _ ->
        val types = model.routeTypes.toSet()
        assertTrue(109 in types, "no S-Bahn (109) routes")
        assertTrue(400 in types, "no U-Bahn (400) routes")
        assertTrue(900 in types, "no tram (900) routes")
        assertTrue(700 in types, "no bus (700) routes")
        assertTrue(1000 in types, "no ferry (1000) routes")
        assertTrue(model.stopCount > 20_000, "suspiciously few stops: ${model.stopCount}")
    }

    @Test
    fun `hauptbahnhof to alexanderplatz`() = ifData { model, router, date ->
        // Stadtbahn S-Bahn leg is ~8 min; regional is quicker still.
        assertCanonical(
            model, router, date,
            fromId = "de:11000:900003201", toId = "de:11000:900100003",
            durationRange = 3..25,
            label = "Hauptbahnhof -> Alexanderplatz",
        )
    }

    @Test
    fun `alexanderplatz to potsdam hauptbahnhof crosses into brandenburg`() = ifData { model, router, date ->
        assertCanonical(
            model, router, date,
            fromId = "de:11000:900100003", toId = "de:12054:900230999",
            durationRange = 25..80,
            label = "Alexanderplatz -> Potsdam Hbf",
        )
    }

    @Test
    fun `curated transfers made it into the model`() = ifData { model, _, _ ->
        assertTrue(model.transferTarget.isNotEmpty(), "no transfer edges at all")
        // VBB curates ~48k usable type-2 rows; with generated footpaths on
        // top the edge count must be well past that.
        assertTrue(model.transferTarget.size > 40_000, "transfer edges: ${model.transferTarget.size}")
    }

    @Test
    fun `umlauts survive the pipeline`() = ifData { model, _, _ ->
        assertTrue(
            model.stopNames.any { it.contains('ü') || it.contains('ß') },
            "expected umlauts/eszett in VBB stop names — UTF-8 path broken?",
        )
    }
}
