package app.krail.bff.tools

import app.krail.bff.track.PolylineCodec
import app.krail.bff.trackdata.ShapesDataset
import app.krail.bff.trackdata.TrackStop
import app.krail.bff.trackdata.TrackStopsDataset
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipInputStream

/**
 * Builds the server-internal tracking datasets (TRACKING_DESIGN.md §7a)
 * from the same GTFS zip bundles the stops job already downloads:
 *
 *  - `track_stops.pb` — platform-level stop directory (raw GTFS ids,
 *    location_type 0 AND 1, parent links). GTFS-R reports platform ids
 *    for trains; the search dataset only has parents + bus stops.
 *  - `shapes_<bundle>.pb` per zip — shape_id → encoded polyline plus a
 *    trip_id → shape_id index. Dedup is the whole trick: thousands of
 *    trips share a handful of shapes.
 *  - `track_manifest.json` — version + per-artifact url/sha256/size; the
 *    BFF's TrackDatasetStore reads this to fetch and hot-swap datasets.
 *
 * Usage (gradle):
 *   ./gradlew :server:buildTrackDataset \
 *     -PgtfsDir=./build/gtfs \
 *     -PoutDir=./build/dist/track \
 *     -Pversion=20260611 \
 *     -PreleaseUrlBase=https://github.com/.../releases/download/stops-20260611
 */
fun main(args: Array<String>) {
    val gtfsDir = args.getOrNull(0) ?: error("usage: BuildTrackDataset <gtfsDir> <outDir> <version> <releaseUrlBase>")
    val outDir = args.getOrNull(1) ?: error("outDir required")
    val version = args.getOrNull(2) ?: error("version required")
    val releaseUrlBase = args.getOrNull(3)?.trimEnd('/') ?: error("release URL base required")

    val gtfsBundles = File(gtfsDir).listFiles { f -> f.isFile && f.name.endsWith(".zip") }
        ?.sortedBy { it.name }
        ?: error("no zip files in $gtfsDir")
    require(gtfsBundles.isNotEmpty()) { "no GTFS zip bundles found in $gtfsDir" }

    val out = File(outDir).apply { mkdirs() }
    val artifacts = mutableListOf<Triple<String, ByteArray, File>>() // name, bytes, file

    println("Building tracking datasets from ${gtfsBundles.size} bundles")

    // --- platform directory (merged across bundles) ---
    val stopsById = LinkedHashMap<String, TrackStop>()
    for (zip in gtfsBundles) {
        val parsed = parseTrackStops(readZipEntries(zip, setOf("stops.txt"))["stops.txt"] ?: continue)
        for (stop in parsed) stopsById.putIfAbsent(stop.stop_id, stop)
    }
    val directory = TrackStopsDataset(
        version = version,
        generated_at = Instant.now().toString(),
        attribution = "Data © Transport for NSW (CC BY 4.0). Modified by KRAIL: GTFS → protobuf.",
        stops = stopsById.values.toList(),
    )
    val directoryBytes = directory.encode()
    val directoryFile = File(out, "track_stops.pb").apply { writeBytes(directoryBytes) }
    artifacts.add(Triple("track_stops.pb", directoryBytes, directoryFile))
    println("  track_stops.pb: ${stopsById.size} stops, ${directoryBytes.size} bytes")

    // --- per-bundle shapes ---
    for (zip in gtfsBundles) {
        val key = zip.name.removeSuffix(".zip")
        val files = readZipEntries(zip, setOf("trips.txt", "shapes.txt"))
        val trips = files["trips.txt"]
        val shapes = files["shapes.txt"]
        if (trips == null || shapes == null) {
            println("  $key: no trips.txt/shapes.txt — skipping shapes")
            continue
        }
        val dataset = buildShapesDataset(key, version, trips, shapes)
        if (dataset.shapes.isEmpty()) {
            println("  $key: 0 shapes — skipping")
            continue
        }
        val bytes = dataset.encode()
        val file = File(out, "shapes_$key.pb").apply { writeBytes(bytes) }
        artifacts.add(Triple("shapes_$key.pb", bytes, file))
        println("  shapes_$key.pb: ${dataset.shapes.size} shapes, ${dataset.trip_index.size} trips, ${bytes.size} bytes")
    }

    // --- manifest ---
    val manifest = buildString {
        append("{\"version\":\"").append(version).append("\",")
        append("\"generated_at\":\"").append(directory.generated_at).append("\",")
        append("\"artifacts\":[")
        artifacts.forEachIndexed { i, (name, bytes, _) ->
            if (i > 0) append(',')
            append("{\"name\":\"").append(name).append("\",")
            append("\"url\":\"").append(releaseUrlBase).append('/').append(name).append("\",")
            append("\"sha256\":\"").append(sha256Hex(bytes)).append("\",")
            append("\"size_bytes\":").append(bytes.size).append('}')
        }
        append("]}")
    }
    File(out, "track_manifest.json").writeText(manifest)
    println("Manifest -> ${File(out, "track_manifest.json").absolutePath}")
}

internal fun parseTrackStops(csv: String): List<TrackStop> {
    val rows = parseCsv(csv)
    val header = rows.firstOrNull() ?: return emptyList()
    val idIdx = header.indexOf("stop_id").also { check(it >= 0) { "stops.txt missing stop_id" } }
    val nameIdx = header.indexOf("stop_name").also { check(it >= 0) { "stops.txt missing stop_name" } }
    val latIdx = header.indexOf("stop_lat")
    val lonIdx = header.indexOf("stop_lon")
    val locTypeIdx = header.indexOf("location_type")
    val parentIdx = header.indexOf("parent_station")

    return rows.drop(1).mapNotNull { row ->
        val id = row.getOrNull(idIdx)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        // Platforms/stops (0) and stations (1) only — GTFS-R never
        // references entrances (2), nodes (3) or boarding areas (4).
        val locationType = row.getOrNull(locTypeIdx)?.toIntOrNull() ?: 0
        if (locationType != 0 && locationType != 1) return@mapNotNull null
        val name = row.getOrNull(nameIdx)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        TrackStop(
            stop_id = id,
            name = name,
            parent_id = row.getOrNull(parentIdx).orEmpty(),
            lat = row.getOrNull(latIdx)?.toDoubleOrNull() ?: 0.0,
            lon = row.getOrNull(lonIdx)?.toDoubleOrNull() ?: 0.0,
        )
    }
}

internal fun buildShapesDataset(key: String, version: String, tripsCsv: String, shapesCsv: String): ShapesDataset {
    // trips.txt: trip_id → shape_id (many → one).
    val tripsRows = parseCsv(tripsCsv)
    val tripsHeader = tripsRows.firstOrNull() ?: return ShapesDataset(version = version, feed = key)
    val tripIdIdx = tripsHeader.indexOf("trip_id")
    val shapeIdIdx = tripsHeader.indexOf("shape_id")
    if (tripIdIdx < 0 || shapeIdIdx < 0) return ShapesDataset(version = version, feed = key)
    val tripIndex = tripsRows.drop(1).mapNotNull { row ->
        val tripId = row.getOrNull(tripIdIdx)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val shapeId = row.getOrNull(shapeIdIdx)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        tripId to shapeId
    }.toMap()

    // shapes.txt: shape_id → ordered points → encoded polyline.
    val shapesRows = parseCsv(shapesCsv)
    val shapesHeader = shapesRows.firstOrNull() ?: return ShapesDataset(version = version, feed = key)
    val sIdIdx = shapesHeader.indexOf("shape_id")
    val sLatIdx = shapesHeader.indexOf("shape_pt_lat")
    val sLonIdx = shapesHeader.indexOf("shape_pt_lon")
    val sSeqIdx = shapesHeader.indexOf("shape_pt_sequence")
    if (sIdIdx < 0 || sLatIdx < 0 || sLonIdx < 0 || sSeqIdx < 0) {
        return ShapesDataset(version = version, feed = key)
    }
    data class Pt(val seq: Int, val lat: Double, val lon: Double)
    val pointsByShape = HashMap<String, MutableList<Pt>>()
    for (row in shapesRows.drop(1)) {
        val id = row.getOrNull(sIdIdx)?.takeIf { it.isNotBlank() } ?: continue
        val lat = row.getOrNull(sLatIdx)?.toDoubleOrNull() ?: continue
        val lon = row.getOrNull(sLonIdx)?.toDoubleOrNull() ?: continue
        val seq = row.getOrNull(sSeqIdx)?.toIntOrNull() ?: continue
        pointsByShape.getOrPut(id) { mutableListOf() }.add(Pt(seq, lat, lon))
    }
    val polylines = pointsByShape.mapValues { (_, pts) ->
        PolylineCodec.encode(pts.sortedBy { it.seq }.map { PolylineCodec.Point(it.lat, it.lon) })
    }

    // Drop trips pointing at shapes that don't exist; drop shapes no trip uses.
    val usedTripIndex = tripIndex.filterValues { it in polylines }
    val usedShapes = polylines.filterKeys { it in usedTripIndex.values.toSet() }

    return ShapesDataset(
        version = version,
        feed = key,
        shapes = usedShapes,
        trip_index = usedTripIndex,
    )
}

private fun readZipEntries(zip: File, names: Set<String>): Map<String, String> {
    val files = mutableMapOf<String, String>()
    ZipInputStream(zip.inputStream()).use { zin ->
        var entry = zin.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && entry.name in names) {
                files[entry.name] = zin.readBytes().decodeToString()
            }
            zin.closeEntry()
            entry = zin.nextEntry
        }
    }
    return files
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
