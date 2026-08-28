package app.krail.bff.region

import app.krail.bff.router.GtfsFeed
import app.krail.bff.router.GtfsParser
import app.krail.bff.router.RouterModel
import app.krail.bff.router.RouterModelBuilder
import app.krail.bff.router.ZipGtfsSource
import java.nio.file.Files
import java.nio.file.Path

/**
 * Ingest for every single-feed region in [RegionRegistry]: one GTFS zip, one
 * id namespace, one [GtfsFeed]. Grew out of the QLD/SEQ ingest — SEQ proved
 * the shape, and Auckland, Wellington, Boston, VBB and PID all publish the
 * same way. Downloading is an offline pipeline concern, never done at
 * request time.
 *
 * VIC is the exception (folder-of-zips bundle, cross-feed stop merging) and
 * keeps its bespoke `vic/VicGtfsIngest`.
 */
object RegionGtfsIngest {

    /** [path] is the region's GTFS zip itself, or a directory containing it. */
    fun resolveZip(spec: RegionSpec, path: Path): Path {
        val zipName = requireNotNull(spec.feedZipName) { "region '${spec.code}' is not single-feed" }
        val zip = if (Files.isDirectory(path)) path.resolve(zipName) else path
        require(Files.isRegularFile(zip)) { "missing GTFS zip: $zip" }
        return zip
    }

    fun loadFeed(spec: RegionSpec, path: Path): GtfsFeed =
        GtfsParser.parseFeed(ZipGtfsSource(spec.code, resolveZip(spec, path)))

    fun buildModel(
        spec: RegionSpec,
        path: Path,
        builtAtEpochSec: Long = 0L,
    ): RouterModel = RouterModelBuilder.build(
        feeds = listOf(loadFeed(spec, path)),
        sourceLabel = spec.sourceLabel,
        builtAtEpochSec = builtAtEpochSec,
    )
}
