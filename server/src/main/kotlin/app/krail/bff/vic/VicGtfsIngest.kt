package app.krail.bff.vic

import app.krail.bff.router.GtfsFeed
import app.krail.bff.router.GtfsParser
import app.krail.bff.router.RouterModel
import app.krail.bff.router.RouterModelBuilder
import app.krail.bff.router.ZipGtfsSource
import java.nio.file.Files
import java.nio.file.Path

/**
 * Ingest for the Victoria GTFS bundle (opendata.transport.vic.gov.au).
 *
 * The published archive is an outer zip of numbered folders, each holding its
 * own google_transit.zip. This expects those folders already extracted to a
 * local directory (`<dir>/<folder>/google_transit.zip`) — downloading is an
 * offline pipeline concern, never done at request time.
 *
 * Folder map: 1=V/Line train, 2=Metro train, 3=Tram, 4=Bus, 5=Regional coach,
 * 6=Regional bus, 10=Interstate, 11=SkyBus. Each folder uses agency_id "1"
 * and overlapping id schemes, so every folder is parsed as its own namespaced
 * feed; the model builder merges stops shared across modes (42 tram/bus stop
 * ids are the same physical stop).
 */
object VicGtfsIngest {

    /** Metropolitan Melbourne scope: metro train, tram, bus, SkyBus. */
    val METRO_FOLDERS = listOf("2", "3", "4", "11")

    fun loadFeeds(rootDir: Path, folders: List<String> = METRO_FOLDERS): List<GtfsFeed> =
        folders.map { folder ->
            val zip = rootDir.resolve(folder).resolve("google_transit.zip")
            require(Files.isRegularFile(zip)) { "missing GTFS zip: $zip" }
            GtfsParser.parseFeed(ZipGtfsSource(folder, zip))
        }

    fun buildModel(
        rootDir: Path,
        folders: List<String> = METRO_FOLDERS,
        builtAtEpochSec: Long = 0L,
    ): RouterModel = RouterModelBuilder.build(
        feeds = loadFeeds(rootDir, folders),
        sourceLabel = "vic-gtfs:${folders.joinToString("+")}",
        builtAtEpochSec = builtAtEpochSec,
    )
}
