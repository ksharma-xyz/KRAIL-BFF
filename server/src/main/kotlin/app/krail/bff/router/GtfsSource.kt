package app.krail.bff.router

import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Abstraction over one GTFS feed's file set. [namespace] identifies the feed
 * within a multi-feed dataset (e.g. VIC folder number); ids are only unique
 * per feed.
 */
interface GtfsSource {
    val namespace: String

    /** Opens a file by GTFS name (e.g. "stops.txt"); null when absent. */
    fun open(fileName: String): InputStream?
}

/** Reads GTFS files out of a google_transit.zip without extracting it. */
class ZipGtfsSource(override val namespace: String, private val zipPath: Path) : GtfsSource {
    override fun open(fileName: String): InputStream? {
        val zip = ZipFile(zipPath.toFile())
        val entry = zip.getEntry(fileName)
        if (entry == null) {
            zip.close()
            return null
        }
        return object : FilterInputStream(zip.getInputStream(entry)) {
            override fun close() {
                super.close()
                zip.close()
            }
        }
    }
}

/** In-memory source for tests and synthetic fixtures. */
class StringGtfsSource(
    override val namespace: String,
    private val files: Map<String, String>,
) : GtfsSource {
    override fun open(fileName: String): InputStream? = files[fileName]?.byteInputStream()
}
