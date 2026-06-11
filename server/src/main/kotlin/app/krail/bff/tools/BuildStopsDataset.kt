package app.krail.bff.tools

import app.krail.bff.proto.data.DatasetStop
import app.krail.bff.proto.data.LatLng
import app.krail.bff.proto.data.StopsDataset
import app.krail.bff.proto.data.TransportMode
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipInputStream

/**
 * Builds a [StopsDataset] protobuf from one or more GTFS zip bundles.
 *
 * Inputs: any number of `*.zip` files containing standard GTFS — must each
 * have at least `stops.txt`. If `routes.txt`, `trips.txt`, and
 * `stop_times.txt` are present, modes are derived per stop. Otherwise modes
 * are left empty for that stop.
 *
 * Output: writes the encoded `StopsDataset` to a target file, plus a
 * `manifest.json` next to it describing the artifact (version, sha256,
 * size, url).
 *
 * Usage (gradle):
 *   ./gradlew :server:buildStopsDataset \
 *     -PgtfsDir=./build/gtfs \
 *     -Poutput=./build/dist/stops.pb \
 *     -Pversion=20260508 \
 *     -PreleaseUrl=https://github.com/.../releases/latest/download/stops.pb
 */
fun main(args: Array<String>) {
    val gtfsDir = args.getOrNull(0) ?: error("usage: BuildStopsDataset <gtfsDir> <output.pb> <version> <releaseUrl>")
    val output = args.getOrNull(1) ?: error("output path required")
    val version = args.getOrNull(2) ?: error("version required")
    val releaseUrl = args.getOrNull(3) ?: error("release URL required")

    val gtfsBundles = File(gtfsDir).listFiles { f -> f.isFile && f.name.endsWith(".zip") }
        ?.toList()
        ?: error("no zip files in $gtfsDir")

    require(gtfsBundles.isNotEmpty()) { "no GTFS zip bundles found in $gtfsDir" }

    println("Reading ${gtfsBundles.size} GTFS bundles from $gtfsDir")

    val stopsById = mutableMapOf<String, DatasetStop>()
    val modesByStopId = mutableMapOf<String, MutableSet<TransportMode>>()

    for (zip in gtfsBundles) {
        println("  ${zip.name}")
        val (stops, modes) = parseBundle(zip)
        for ((id, stop) in stops) {
            // Deduplicate by stop_id; first bundle wins for name/coords.
            stopsById.putIfAbsent(id, stop)
        }
        for ((id, modeSet) in modes) {
            modesByStopId.getOrPut(id) { mutableSetOf() }.addAll(modeSet)
        }
    }

    // Apply derived modes.
    val finalStops = stopsById.values.map { stop ->
        val modes = modesByStopId[stop.stop_id]?.sortedBy { it.value } ?: emptyList()
        stop.copy(modes = modes)
    }

    val dataset = StopsDataset(
        version = version,
        generated_at = Instant.now().toString(),
        attribution = "Data © Transport for NSW (CC BY 4.0). Modified by KRAIL: GTFS → protobuf.",
        stops = finalStops,
    )

    val outFile = File(output).apply { parentFile?.mkdirs() }
    val bytes = dataset.encode()
    outFile.writeBytes(bytes)

    val sha256 = sha256(bytes)
    val manifest = buildString {
        append('{')
        append("\"version\":\"").append(version).append("\",")
        append("\"sha256\":\"").append(sha256).append("\",")
        append("\"url\":\"").append(releaseUrl).append("\",")
        append("\"size_bytes\":").append(bytes.size).append(',')
        append("\"compression\":\"none\",")
        append("\"generated_at\":\"").append(dataset.generated_at).append("\",")
        append("\"stop_count\":").append(finalStops.size)
        append('}')
    }
    val manifestFile = File(outFile.parentFile, "manifest.json")
    manifestFile.writeText(manifest)

    println("Wrote ${finalStops.size} stops -> ${outFile.absolutePath} (${bytes.size} bytes)")
    println("Manifest -> ${manifestFile.absolutePath}")
    println("sha256: $sha256")
}

private fun sha256(bytes: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

private data class BundleData(
    val stops: Map<String, DatasetStop>,
    val modesByStopId: Map<String, Set<TransportMode>>,
)

private fun parseBundle(zip: File): BundleData {
    val files = mutableMapOf<String, String>()
    ZipInputStream(zip.inputStream()).use { zin ->
        var entry = zin.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && entry.name in TARGET_FILES) {
                files[entry.name] = zin.readBytes().decodeToString()
            }
            zin.closeEntry()
            entry = zin.nextEntry
        }
    }

    val stops = parseStops(files["stops.txt"] ?: error("${zip.name}: stops.txt missing"))

    val modesByStopId = if (files.containsKey("routes.txt") && files.containsKey("trips.txt") && files.containsKey("stop_times.txt")) {
        deriveModes(
            routes = files["routes.txt"]!!,
            trips = files["trips.txt"]!!,
            stopTimes = files["stop_times.txt"]!!,
        )
    } else {
        emptyMap()
    }

    return BundleData(stops, modesByStopId)
}

private val TARGET_FILES = setOf("stops.txt", "routes.txt", "trips.txt", "stop_times.txt")

private fun parseStops(csv: String): Map<String, DatasetStop> {
    val rows = parseCsv(csv)
    val header = rows.firstOrNull() ?: return emptyMap()
    val idIdx = header.indexOf("stop_id").also { check(it >= 0) { "stops.txt missing stop_id" } }
    val nameIdx = header.indexOf("stop_name").also { check(it >= 0) { "stops.txt missing stop_name" } }
    val latIdx = header.indexOf("stop_lat")
    val lonIdx = header.indexOf("stop_lon")
    val locTypeIdx = header.indexOf("location_type")

    val out = LinkedHashMap<String, DatasetStop>()
    for (row in rows.drop(1)) {
        val rawId = row.getOrNull(idIdx)?.takeIf { it.isNotBlank() } ?: continue
        // Skip station nodes etc.; KRAIL search wants stops/platforms only.
        val locationType = row.getOrNull(locTypeIdx)?.toIntOrNull() ?: 0
        if (locationType != 0 && locationType != 1) continue

        val name = row.getOrNull(nameIdx)?.takeIf { it.isNotBlank() } ?: continue
        val namespacedId = "NSW:$rawId"
        val lat = row.getOrNull(latIdx)?.toDoubleOrNull()
        val lon = row.getOrNull(lonIdx)?.toDoubleOrNull()
        val position = if (lat != null && lon != null) LatLng(lat = lat, lon = lon) else null

        out[namespacedId] = DatasetStop(stop_id = namespacedId, name = name, position = position)
    }
    return out
}

private fun deriveModes(routes: String, trips: String, stopTimes: String): Map<String, Set<TransportMode>> {
    val routesRows = parseCsv(routes)
    val routesHeader = routesRows.firstOrNull() ?: return emptyMap()
    val routeIdIdx = routesHeader.indexOf("route_id")
    val routeTypeIdx = routesHeader.indexOf("route_type")
    if (routeIdIdx < 0 || routeTypeIdx < 0) return emptyMap()

    val routeTypeById = routesRows.drop(1).mapNotNull { row ->
        val id = row.getOrNull(routeIdIdx)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val type = row.getOrNull(routeTypeIdx)?.toIntOrNull() ?: return@mapNotNull null
        id to type
    }.toMap()

    val tripsRows = parseCsv(trips)
    val tripsHeader = tripsRows.firstOrNull() ?: return emptyMap()
    val tripIdIdx = tripsHeader.indexOf("trip_id")
    val tripRouteIdIdx = tripsHeader.indexOf("route_id")
    if (tripIdIdx < 0 || tripRouteIdIdx < 0) return emptyMap()

    val routeIdByTrip = tripsRows.drop(1).mapNotNull { row ->
        val tid = row.getOrNull(tripIdIdx)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val rid = row.getOrNull(tripRouteIdIdx)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        tid to rid
    }.toMap()

    val stopTimesRows = parseCsv(stopTimes)
    val stHeader = stopTimesRows.firstOrNull() ?: return emptyMap()
    val stTripIdIdx = stHeader.indexOf("trip_id")
    val stStopIdIdx = stHeader.indexOf("stop_id")
    if (stTripIdIdx < 0 || stStopIdIdx < 0) return emptyMap()

    val out = mutableMapOf<String, MutableSet<TransportMode>>()
    for (row in stopTimesRows.drop(1)) {
        val tid = row.getOrNull(stTripIdIdx) ?: continue
        val sid = row.getOrNull(stStopIdIdx)?.takeIf { it.isNotBlank() } ?: continue
        val rid = routeIdByTrip[tid] ?: continue
        val rtype = routeTypeById[rid] ?: continue
        val mode = mapRouteTypeToMode(rtype) ?: continue
        out.getOrPut("NSW:$sid") { mutableSetOf() }.add(mode)
    }
    return out
}

/** GTFS route_type → KRAIL TransportMode. Returns null for unmapped types. */
private fun mapRouteTypeToMode(routeType: Int): TransportMode? = when (routeType) {
    0 -> TransportMode.LIGHT_RAIL
    1 -> TransportMode.METRO
    2 -> TransportMode.TRAIN
    3 -> TransportMode.BUS
    4 -> TransportMode.FERRY
    11 -> TransportMode.BUS // trolleybus
    12 -> TransportMode.METRO // monorail
    // Extended GTFS route types
    100, 101, 102, 103, 104, 105, 106, 107, 108, 109 -> TransportMode.TRAIN
    200, 201, 202, 203, 204, 205, 206, 207, 208, 209 -> TransportMode.COACH
    400, 401, 402, 403, 404, 405 -> TransportMode.METRO
    700, 701, 702, 703, 704, 705, 706, 707, 708, 709, 710, 711, 712, 713, 714, 715, 716 -> TransportMode.BUS
    800 -> TransportMode.BUS // trolleybus
    900, 901, 902, 903, 904, 905, 906 -> TransportMode.LIGHT_RAIL
    1000, 1001, 1002, 1003, 1004, 1005, 1006, 1007, 1008, 1009, 1010, 1011, 1012, 1013, 1014, 1015, 1016, 1017, 1018, 1019, 1020, 1021 -> TransportMode.FERRY
    else -> null
}

// CSV parsing lives in GtfsCsv.kt.
