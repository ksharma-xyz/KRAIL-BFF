package app.krail.bff.qld

import app.krail.bff.router.GtfsFeed
import app.krail.bff.router.GtfsParser
import app.krail.bff.router.RouterModel
import app.krail.bff.router.RouterModelBuilder
import app.krail.bff.router.ZipGtfsSource
import java.nio.file.Files
import java.nio.file.Path

/**
 * Ingest for the Translink SEQ GTFS feed (gtfsrt.api.translink.com.au).
 *
 * Unlike VIC's zip-of-zips, SEQ publishes one flat SEQ_GTFS.zip covering
 * every mode — bus, Queensland Rail Citytrain, G:link tram, ferry — with a
 * single id namespace, so the whole region is one [GtfsFeed]. Downloading is
 * an offline pipeline concern, never done at request time.
 *
 * Feed shape (drop 2026-08-27): no transfers.txt (every interchange edge is
 * generated), no frequencies.txt, standard route_types only (0 tram, 2 rail,
 * 3 bus, 4 ferry), platforms are numeric stop ids with `place_*` parent
 * stations. See docs/reference/QLD_ROUTER_NOTES.md for the measured quirks.
 */
object QldGtfsIngest {

    const val FEED_ZIP_NAME = "SEQ_GTFS.zip"

    /** [path] is the SEQ_GTFS.zip itself, or a directory containing it. */
    fun resolveZip(path: Path): Path {
        val zip = if (Files.isDirectory(path)) path.resolve(FEED_ZIP_NAME) else path
        require(Files.isRegularFile(zip)) { "missing GTFS zip: $zip" }
        return zip
    }

    fun loadFeed(path: Path): GtfsFeed = GtfsParser.parseFeed(ZipGtfsSource("seq", resolveZip(path)))

    fun buildModel(
        path: Path,
        builtAtEpochSec: Long = 0L,
    ): RouterModel = RouterModelBuilder.build(
        feeds = listOf(loadFeed(path)),
        sourceLabel = "qld-gtfs:seq",
        builtAtEpochSec = builtAtEpochSec,
    )
}
