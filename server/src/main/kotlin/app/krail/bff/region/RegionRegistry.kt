package app.krail.bff.region

import java.time.ZoneId

/**
 * Every BFF-routed journey-planning region. VIC (multi-feed folder bundle,
 * bespoke ingest in `vic/`) plus the single-feed regions, which share
 * [RegionGtfsIngest] end to end.
 *
 * A region here is wiring only — nothing serves until its
 * `{CODE}_ROUTER_SNAPSHOT` env var points at a built snapshot, so listing a
 * region is free in production. Feed sources and per-region quirks:
 * docs/reference/WORLD_REGIONS_NOTES.md (QLD_ROUTER_NOTES.md for SEQ,
 * VIC_ROUTER_DESIGN.md for the core + VIC).
 */
object RegionRegistry {

    val vic = RegionSpec(
        code = "vic",
        displayName = "Melbourne (PTV)",
        zone = ZoneId.of("Australia/Melbourne"),
        idPrefix = "VIC",
        sourceLabel = "vic-gtfs",
        feedZipName = null, // folder-of-zips bundle: vic/VicGtfsIngest
        canonicalPairs = listOf(
            CanonicalPair("Flinders St -> Melbourne Central", "vic:rail:FSS", "vic:rail:MCE"),
            CanonicalPair("Flagstaff -> Parliament (City Loop)", "vic:rail:FGS", "vic:rail:PAR"),
            CanonicalPair("Southern Cross -> Flinders St", "vic:rail:SSS", "vic:rail:FSS"),
            // V/Line pair — only present when folder 1 is ingested.
            CanonicalPair("Southern Cross -> Geelong (V/Line)", "vic:rail:SSS", "vic:rail:GEL"),
        ),
    )

    val qld = RegionSpec(
        code = "qld",
        displayName = "Brisbane / SEQ (Translink)",
        zone = ZoneId.of("Australia/Brisbane"),
        idPrefix = "QLD",
        sourceLabel = "qld-gtfs:seq",
        feedZipName = "SEQ_GTFS.zip",
        canonicalPairs = listOf(
            CanonicalPair("Central -> Roma Street (rail)", "place_censta", "place_romsta"),
            CanonicalPair("West End -> New Farm Park (CityCat)", "place_westft", "place_newfft"),
            CanonicalPair("Broadbeach South -> Cavill Ave (G:link)", "place_brsstn", "place_cavsta"),
            CanonicalPair("UQ Chancellors Pl -> Eagle Junction (bus+rail)", "place_intuq", "place_egjsta"),
        ),
    )

    val akl = RegionSpec(
        code = "akl",
        displayName = "Auckland (AT)",
        zone = ZoneId.of("Pacific/Auckland"),
        idPrefix = "AKL",
        sourceLabel = "akl-gtfs:at",
        feedZipName = "gtfs.zip",
    )

    val wlg = RegionSpec(
        code = "wlg",
        displayName = "Wellington (Metlink)",
        zone = ZoneId.of("Pacific/Auckland"),
        idPrefix = "WLG",
        sourceLabel = "wlg-gtfs:metlink",
        feedZipName = "full.zip",
    )

    val bos = RegionSpec(
        code = "bos",
        displayName = "Boston (MBTA)",
        zone = ZoneId.of("America/New_York"),
        idPrefix = "BOS",
        sourceLabel = "bos-gtfs:mbta",
        feedZipName = "MBTA_GTFS.zip",
    )

    val ber = RegionSpec(
        code = "ber",
        displayName = "Berlin / Brandenburg (VBB)",
        zone = ZoneId.of("Europe/Berlin"),
        idPrefix = "BER",
        sourceLabel = "ber-gtfs:vbb",
        feedZipName = "GTFS.zip",
    )

    val prg = RegionSpec(
        code = "prg",
        displayName = "Prague (PID)",
        zone = ZoneId.of("Europe/Prague"),
        idPrefix = "PRG",
        sourceLabel = "prg-gtfs:pid",
        feedZipName = "PID_GTFS.zip",
    )

    val all: List<RegionSpec> = listOf(vic, qld, akl, wlg, bos, ber, prg)

    val singleFeed: List<RegionSpec> = all.filter { it.singleFeed }

    fun byCode(code: String): RegionSpec? = all.firstOrNull { it.code == code }
}
