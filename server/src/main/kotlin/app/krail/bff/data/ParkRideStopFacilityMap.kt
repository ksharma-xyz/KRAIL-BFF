package app.krail.bff.data

/**
 * Static lookup of NSW park-and-ride facilities indexed by transit stop ID.
 *
 * **Why this lives on the server side:**
 *
 * The KRAIL app's home screen renders a parking card per saved trip. Each
 * stop can have 1–3 distinct park-and-ride facilities (e.g. Tallawong has
 * P1/P2/P3 = facilities 26/27/28). Some stop IDs alias the same facility
 * (e.g. Hornsby has stopIds 207720 + 207763, both → facility 25). This
 * mapping was previously held client-side via the Firebase Remote Config
 * flag `NSW_PARK_RIDE_FACILITIES`; moving it server-side lets the KRAIL
 * app delete that flag, the `RealNswParkRideFacilityManager`, and the
 * per-card facility resolution code.
 *
 * The mapping is consumed by `GET /v1/parking/availability?stopIds=...`,
 * which resolves stop IDs → facility IDs internally and fans out NSW
 * upstream calls accordingly.
 *
 * **How updates happen:**
 *
 * NSW opens / closes / renames a park-and-ride maybe a handful of times
 * per year. Update this file, redeploy the BFF, and every consumer (KRAIL
 * Android, KRAIL iOS, future web) sees the change without an app release.
 *
 * **Long-term (deferred):**
 *
 * Once `KRAIL-API-PROTO` v0.2.0 ships a `repeated string park_ride_facility_ids`
 * field on `DatasetStop`, this lookup moves into the weekly stops dataset
 * (.pb on GitHub Releases). The app reads from its already-cached dataset
 * and never hits this server-side table. See
 * `docs/handover/PARK_RIDE_BATCH_HANDOVER.md` §"Deferred to Phase B" for
 * the migration plan.
 */
internal object ParkRideStopFacilityMap {

    /**
     * Stop ID → distinct facility IDs reachable from that stop.
     * Lookup is O(1); use [facilitiesFor] rather than indexing the map
     * directly so the empty-list semantics ("known map, no facilities")
     * are explicit.
     */
    private val byStop: Map<String, List<String>> = buildMap {
        // Ashfield
        put("213110", listOf("486"))
        // Bella Vista
        put("2153478", listOf("31"))
        // Beverly Hills
        put("220910", listOf("35"))
        // Brookvale
        put("210017", listOf("490"))
        // Campbelltown — Farrow Rd (north) + Hurley St share the same stop
        put("256020", listOf("19", "20"))
        // Cherrybrook
        put("2126158", listOf("33"))
        // Dee Why
        put("209913", listOf("13"))
        // Edmondson Park (south)
        put("217426", listOf("17"))
        // Emu Plains
        put("275020", listOf("36"))
        // Gordon Henry St (north)
        put("207210", listOf("6"))
        // Gosford — two stop IDs alias facility 8
        put("225041", listOf("8"))
        put("225040", listOf("8"))
        // Hills Showground
        put("2154392", listOf("32"))
        // Hornsby — two stop IDs alias facility 25
        put("207720", listOf("25"))
        put("207763", listOf("25"))
        // Kellyville — north + south at the same stop
        put("2155382", listOf("29", "30"))
        // Kiama
        put("253330", listOf("7"))
        // Kogarah
        put("221710", listOf("487"))
        // Leppington
        put("217933", listOf("16"))
        // Lindfield Village Green
        put("207010", listOf("34"))
        // Manly Vale — two stop IDs alias facility 489
        put("209325", listOf("489"))
        put("209324", listOf("489"))
        // Mona Vale — two stop IDs alias facility 12
        put("2103108", listOf("12"))
        put("210318", listOf("12"))
        // Narrabeen
        put("210115", listOf("11"))
        // Penrith — at-grade + multi-level at two stop IDs each
        put("275075", listOf("21", "22"))
        put("275010", listOf("21", "22"))
        // Revesby
        put("221210", listOf("9"))
        // Riverwood
        put("221010", listOf("37"))
        // Schofields — two stop IDs alias facility 24
        put("2762106", listOf("24"))
        put("276220", listOf("24"))
        // Seven Hills — two stop IDs alias facility 488
        put("214732", listOf("488"))
        put("214710", listOf("488"))
        // St Marys
        put("276010", listOf("18"))
        // Sutherland — three stop IDs alias facility 15
        put("223210", listOf("15"))
        put("2232126", listOf("15"))
        put("2232254", listOf("15"))
        // Tallawong — three facilities at the same stop (P1, P2, P3)
        put("2155384", listOf("26", "27", "28"))
        // Warriewood
        put("210120", listOf("10"))
        // Warwick Farm
        put("217010", listOf("23"))
        // West Ryde
        put("211420", listOf("14"))
    }

    /**
     * Facility ID → display name. Useful when the BFF wants to enrich
     * responses with the human-readable facility name (e.g. "Tallawong P1").
     * The mapping is many-to-one in the source data (multiple stops alias
     * the same facility); deduped here.
     */
    val facilityNames: Map<String, String> = mapOf(
        "486" to "Park&Ride - Ashfield",
        "31"  to "Bella Vista",
        "35"  to "Beverly Hills",
        "490" to "Brookvale",
        "19"  to "Campbelltown Farrow Rd (north)",
        "20"  to "Campbelltown Hurley St",
        "33"  to "Cherrybrook",
        "13"  to "Dee Why",
        "17"  to "Edmondson Park (south)",
        "36"  to "Emu Plains",
        "6"   to "Gordon Henry St (north)",
        "8"   to "Gosford",
        "32"  to "Hills Showground",
        "25"  to "Hornsby",
        "29"  to "Kellyville (north)",
        "30"  to "Kellyville (south)",
        "7"   to "Kiama",
        "487" to "Kogarah",
        "16"  to "Leppington",
        "34"  to "Lindfield Village Green",
        "489" to "Manly Vale",
        "12"  to "Mona Vale",
        "11"  to "Narrabeen",
        "21"  to "Penrith (at-grade)",
        "22"  to "Penrith (multi-level)",
        "9"   to "Revesby",
        "37"  to "Riverwood",
        "24"  to "Schofields",
        "488" to "Seven Hills",
        "18"  to "St Marys",
        "15"  to "Sutherland",
        "26"  to "Tallawong P1",
        "27"  to "Tallawong P2",
        "28"  to "Tallawong P3",
        "10"  to "Warriewood",
        "23"  to "Warwick Farm",
        "14"  to "West Ryde",
    )

    /** All stop IDs we have a mapping for. Used by tests + diagnostics. */
    val knownStopIds: Set<String> = byStop.keys

    /**
     * Returns the list of facility IDs at [stopId], or `null` if no
     * mapping exists. Empty list is never returned — a known stop always
     * has at least one facility (otherwise it wouldn't be in the map).
     */
    fun facilitiesFor(stopId: String): List<String>? = byStop[stopId]

    /**
     * Returns the display name for [facilityId] (e.g. "Tallawong P1") or
     * `null` if unknown. Safe to call with arbitrary input — no exception.
     */
    fun nameFor(facilityId: String): String? = facilityNames[facilityId]
}
