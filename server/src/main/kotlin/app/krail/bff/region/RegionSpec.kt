package app.krail.bff.region

import java.time.ZoneId

/**
 * A canonical journey a human can sanity-check against the region's published
 * timetable. RouterBench plans every pair whose ids exist in the dataset.
 */
class CanonicalPair(val label: String, val fromId: String, val toId: String)

/**
 * Everything region-specific about a BFF-routed journey-planning region.
 * The router core is city-agnostic; a region contributes only ingest wiring,
 * a timezone, a public stop-id prefix and its endpoint code. Adding a
 * single-feed city = one entry in [RegionRegistry].
 *
 * Naming conventions derived from [code] (lowercase, also the path segment of
 * `/api/v1/{code}/trip/plan-proto`):
 * - `{CODE}_ROUTER_SNAPSHOT` env var (or `{code}.routerSnapshot` config)
 *   feature-gates the region's routes, exactly like VIC/QLD always worked.
 * - `{CODE}_GTFS_DIR` env var gates the region's golden tests.
 * - `router-snapshot-{code}.bin` is the default snapshot file name.
 */
class RegionSpec(
    val code: String,
    val displayName: String,
    val zone: ZoneId,
    /** Public stop-id namespace, without the colon (e.g. "VIC", "AKL"). */
    val idPrefix: String,
    val sourceLabel: String,
    /**
     * Single-feed regions: the default zip name when the ingest is handed a
     * directory. Null = not single-feed (VIC's folder bundle keeps its own
     * bespoke ingest in `vic/`).
     */
    val feedZipName: String? = null,
    val canonicalPairs: List<CanonicalPair> = emptyList(),
) {
    val snapshotEnvVar: String get() = "${code.uppercase()}_ROUTER_SNAPSHOT"
    val snapshotConfigKey: String get() = "$code.routerSnapshot"
    val gtfsDirEnvVar: String get() = "${code.uppercase()}_GTFS_DIR"
    val snapshotFileName: String get() = "router-snapshot-$code.bin"
    val singleFeed: Boolean get() = feedZipName != null
}
