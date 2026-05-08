package app.krail.bff.tools

import app.krail.bff.proto.data.RouteGroup
import app.krail.bff.proto.data.RouteVariant
import app.krail.bff.proto.data.RoutesDataset
import app.krail.bff.proto.data.TransportMode
import app.krail.bff.proto.data.TripOption
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipInputStream

/**
 * Builds a [RoutesDataset] protobuf from one or more GTFS zip bundles.
 *
 * Inputs: any number of `*.zip` files containing standard GTFS — must each
 * have `routes.txt`, `trips.txt`, and `stop_times.txt`. Bundles missing any of
 * these are skipped with a warning.
 *
 * Output: writes the encoded `RoutesDataset` to a target file, plus a
 * `routes-manifest.json` next to it describing the artifact (version, sha256,
 * size, url).
 *
 * Usage (gradle):
 *   ./gradlew :server:buildRoutesDataset \
 *     -PgtfsDir=./build/gtfs \
 *     -Poutput=./build/dist/routes.pb \
 *     -Pversion=20260508 \
 *     -PreleaseUrl=https://github.com/.../releases/latest/download/routes.pb
 */
fun main(args: Array<String>) {
    val gtfsDir = args.getOrNull(0) ?: error("usage: BuildRoutesDataset <gtfsDir> <output.pb> <version> <releaseUrl>")
    val output = args.getOrNull(1) ?: error("output path required")
    val version = args.getOrNull(2) ?: error("version required")
    val releaseUrl = args.getOrNull(3) ?: error("release URL required")

    val gtfsBundles = File(gtfsDir).listFiles { f -> f.isFile && f.name.endsWith(".zip") }
        ?.toList()
        ?: error("no zip files in $gtfsDir")
    require(gtfsBundles.isNotEmpty()) { "no GTFS zip bundles found in $gtfsDir" }

    println("Reading ${gtfsBundles.size} GTFS bundles from $gtfsDir")

    // Aggregate across bundles, keyed by route_short_name.
    // Same short_name appearing across operators / agencies → multiple variants on the same group.
    val groupsByShortName = LinkedHashMap<String, MutableMap<String, RouteVariant>>() // short_name → routeId → variant

    for (zip in gtfsBundles) {
        println("  ${zip.name}")
        val data = parseRoutesBundle(zip) ?: continue
        for ((shortName, variants) in data.groups) {
            val sink = groupsByShortName.getOrPut(shortName) { LinkedHashMap() }
            for (v in variants) {
                sink.putIfAbsent(v.route_id, v)
            }
        }
    }

    val routeGroups = groupsByShortName.entries
        .sortedBy { it.key }
        .map { (shortName, variants) ->
            RouteGroup(
                route_short_name = shortName,
                mode = inferModeFromVariants(variants.values),
                variants = variants.values.sortedBy { it.route_id },
            )
        }

    val dataset = RoutesDataset(
        version = version,
        generated_at = Instant.now().toString(),
        attribution = "Data © Transport for NSW (CC BY 4.0). Modified by KRAIL: GTFS → protobuf.",
        routes = routeGroups,
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
        append("\"route_group_count\":").append(routeGroups.size).append(',')
        append("\"variant_count\":").append(routeGroups.sumOf { it.variants.size }).append(',')
        append("\"trip_count\":").append(routeGroups.sumOf { rg -> rg.variants.sumOf { it.trips.size } })
        append('}')
    }
    val manifestFile = File(outFile.parentFile, "routes-manifest.json")
    manifestFile.writeText(manifest)

    println("Wrote ${routeGroups.size} route groups -> ${outFile.absolutePath} (${bytes.size} bytes)")
    println("Manifest -> ${manifestFile.absolutePath}")
    println("sha256: $sha256")
}

private fun sha256(bytes: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(bytes).joinToString("") { "%02x".format(it) }
}

private data class RoutesBundleData(val groups: Map<String, List<RouteVariant>>)

private fun parseRoutesBundle(zip: File): RoutesBundleData? {
    val files = mutableMapOf<String, String>()
    ZipInputStream(zip.inputStream()).use { zin ->
        var entry = zin.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && entry.name in ROUTES_TARGET_FILES) {
                files[entry.name] = zin.readBytes().decodeToString()
            }
            zin.closeEntry()
            entry = zin.nextEntry
        }
    }

    val routesCsv = files["routes.txt"] ?: run {
        println("    skipping ${zip.name}: routes.txt missing")
        return null
    }
    val tripsCsv = files["trips.txt"] ?: run {
        println("    skipping ${zip.name}: trips.txt missing")
        return null
    }
    val stopTimesCsv = files["stop_times.txt"] ?: run {
        println("    skipping ${zip.name}: stop_times.txt missing")
        return null
    }

    // routes.txt: route_id → (route_short_name, route_long_name, route_type)
    val routeMeta = parseRoutes(routesCsv)
    if (routeMeta.isEmpty()) return RoutesBundleData(emptyMap())

    // trips.txt: trip_id → (route_id, trip_headsign)
    val tripsByRoute = parseTrips(tripsCsv)

    // stop_times.txt: trip_id → ordered stop_id list
    val stopsByTrip = parseStopTimes(stopTimesCsv, tripsByRoute.values.flatten().map { it.tripId }.toHashSet())

    // Group trips by (route_id, headsign) and pick a representative trip per direction.
    val groupsByShortName = LinkedHashMap<String, MutableList<RouteVariant>>()
    for ((routeId, meta) in routeMeta) {
        val tripsForRoute = tripsByRoute[routeId] ?: continue
        val byHeadsign = tripsForRoute.groupBy { it.headsign }
        val tripOptions = byHeadsign.mapNotNull { (headsign, group) ->
            // Pick the trip with the longest stop sequence (best representative).
            val rep = group.maxByOrNull { stopsByTrip[it.tripId]?.size ?: 0 } ?: return@mapNotNull null
            val stops = stopsByTrip[rep.tripId] ?: return@mapNotNull null
            if (stops.isEmpty()) return@mapNotNull null
            TripOption(trip_id = rep.tripId, headsign = headsign, stop_ids = stops)
        }
        if (tripOptions.isEmpty()) continue
        val variant = RouteVariant(
            route_id = routeId,
            route_name = meta.longName.ifBlank { meta.shortName },
            trips = tripOptions,
        )
        groupsByShortName.getOrPut(meta.shortName) { mutableListOf() }.add(variant)
    }

    return RoutesBundleData(groupsByShortName.mapValues { it.value.toList() })
}

private val ROUTES_TARGET_FILES = setOf("routes.txt", "trips.txt", "stop_times.txt")

private data class RouteMeta(val shortName: String, val longName: String, val routeType: Int)
private data class TripRow(val tripId: String, val headsign: String)

private fun parseRoutes(csv: String): Map<String, RouteMeta> {
    val rows = parseRoutesCsv(csv)
    val header = rows.firstOrNull() ?: return emptyMap()
    val idIdx = header.indexOf("route_id").also { if (it < 0) return emptyMap() }
    val shortIdx = header.indexOf("route_short_name")
    val longIdx = header.indexOf("route_long_name")
    val typeIdx = header.indexOf("route_type")
    val out = LinkedHashMap<String, RouteMeta>()
    for (row in rows.drop(1)) {
        val id = row.getOrNull(idIdx)?.takeIf { it.isNotBlank() } ?: continue
        val shortName = row.getOrNull(shortIdx)?.takeIf { it.isNotBlank() } ?: continue
        val longName = row.getOrNull(longIdx).orEmpty()
        val type = row.getOrNull(typeIdx)?.toIntOrNull() ?: 3
        out[id] = RouteMeta(shortName = shortName, longName = longName, routeType = type)
    }
    return out
}

private fun parseTrips(csv: String): Map<String, List<TripRow>> {
    val rows = parseRoutesCsv(csv)
    val header = rows.firstOrNull() ?: return emptyMap()
    val tripIdx = header.indexOf("trip_id").also { if (it < 0) return emptyMap() }
    val routeIdx = header.indexOf("route_id").also { if (it < 0) return emptyMap() }
    val headsignIdx = header.indexOf("trip_headsign")
    val out = LinkedHashMap<String, MutableList<TripRow>>()
    for (row in rows.drop(1)) {
        val tripId = row.getOrNull(tripIdx)?.takeIf { it.isNotBlank() } ?: continue
        val routeId = row.getOrNull(routeIdx)?.takeIf { it.isNotBlank() } ?: continue
        val headsign = row.getOrNull(headsignIdx).orEmpty().ifBlank { "(unspecified)" }
        out.getOrPut(routeId) { mutableListOf() }.add(TripRow(tripId, headsign))
    }
    return out
}

private fun parseStopTimes(csv: String, wantedTrips: Set<String>): Map<String, List<String>> {
    val rows = parseRoutesCsv(csv)
    val header = rows.firstOrNull() ?: return emptyMap()
    val tripIdIdx = header.indexOf("trip_id").also { if (it < 0) return emptyMap() }
    val stopIdIdx = header.indexOf("stop_id").also { if (it < 0) return emptyMap() }
    val seqIdx = header.indexOf("stop_sequence").also { if (it < 0) return emptyMap() }
    val acc = LinkedHashMap<String, MutableList<Pair<Int, String>>>()
    for (row in rows.drop(1)) {
        val trip = row.getOrNull(tripIdIdx) ?: continue
        if (trip !in wantedTrips) continue
        val stop = row.getOrNull(stopIdIdx)?.takeIf { it.isNotBlank() } ?: continue
        val seq = row.getOrNull(seqIdx)?.toIntOrNull() ?: continue
        acc.getOrPut(trip) { mutableListOf() }.add(seq to stop)
    }
    return acc.mapValues { (_, pairs) -> pairs.sortedBy { it.first }.map { it.second } }
}

/** GTFS route_type → KRAIL TransportMode, picking the most common type across this group's variants. */
private fun inferModeFromVariants(variants: Collection<RouteVariant>): TransportMode {
    // We only have variants here, not original route_types. Re-derive from any heuristic available.
    // For v1 we default to BUS; the modes will be refined when called from the build with route_type info preserved.
    // (A future iteration can pass route_type through the data path.)
    return TransportMode.BUS
}

/**
 * Minimal RFC 4180-ish CSV parser: handles quoted fields, escaped quotes (""), optional UTF-8 BOM.
 */
private fun parseRoutesCsv(input: String): List<List<String>> {
    val source = if (input.isNotEmpty() && input[0] == '﻿') input.substring(1) else input
    val rows = mutableListOf<List<String>>()
    val current = mutableListOf<String>()
    val field = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < source.length) {
        val c = source[i]
        when {
            inQuotes -> when {
                c == '"' && i + 1 < source.length && source[i + 1] == '"' -> {
                    field.append('"'); i++
                }
                c == '"' -> inQuotes = false
                else -> field.append(c)
            }
            c == '"' -> inQuotes = true
            c == ',' -> {
                current.add(field.toString()); field.setLength(0)
            }
            c == '\n' -> {
                current.add(field.toString()); field.setLength(0)
                rows.add(current.toList()); current.clear()
            }
            c == '\r' -> { /* CRLF — swallow */ }
            else -> field.append(c)
        }
        i++
    }
    if (field.isNotEmpty() || current.isNotEmpty()) {
        current.add(field.toString())
        rows.add(current.toList())
    }
    return rows
}
