package app.krail.bff.track

import app.krail.bff.trackdata.ShapesDataset
import app.krail.bff.trackdata.TrackStop
import app.krail.bff.trackdata.TrackStopsDataset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory tracking datasets (TRACKING_DESIGN.md §7a): the platform-level
 * stop directory and per-mode shapes polylines, built weekly by the
 * stops-dataset job.
 *
 * Sources, in priority order:
 *  - `TRACK_DATASET_DIR` — local files (dev: point at the buildTrackDataset
 *    output dir). Loaded once, never re-checked.
 *  - `TRACK_DATASET_MANIFEST_URL` — track_manifest.json on GitHub Releases.
 *    Artifacts are fetched lazily (directory on first stop lookup, each
 *    shapes file on first geometry request for that mode), sha256-verified,
 *    and hot-swapped when a manifest re-check (every 6 h) shows a new
 *    version.
 *
 * Everything degrades gracefully: with neither source configured, or on any
 * load failure, lookups return null — tracking still works, stops fall back
 * to the bundled search dataset and geometry to STOP_STRAIGHT_LINES.
 * Failures are negative-cached for 5 minutes so a broken source can't be
 * hammered once per poll.
 */
class TrackDatasetStore(
    private val localDir: String? = System.getenv("TRACK_DATASET_DIR"),
    private val manifestUrl: String? = System.getenv("TRACK_DATASET_MANIFEST_URL"),
    private val recheckMillis: Long = 6 * 3_600_000L,
    private val failureRetryMillis: Long = 5 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fetcher: (String) -> ByteArray = ::httpGet,
) {
    private val logger = LoggerFactory.getLogger(TrackDatasetStore::class.java)
    private val mutex = Mutex()
    private val enabled = !localDir.isNullOrBlank() || !manifestUrl.isNullOrBlank()

    /** value == null means the last attempt failed (retry after [at] ages out). */
    private class Loaded<T>(val value: T?, val at: Long)

    @Volatile private var directory: Loaded<Map<String, TrackStop>>? = null
    @Volatile private var manifest: Loaded<TrackManifest>? = null
    private val shapesByKey = ConcurrentHashMap<String, Loaded<ShapesDataset>>()

    suspend fun stop(stopId: String): TrackStop? {
        if (!enabled || stopId.isBlank()) return null
        return directoryMap()?.get(stopId)
    }

    /**
     * Encoded polyline for a realtime trip, or null when the trip (or the
     * whole mode's dataset) is unknown — callers fall back to straight lines.
     */
    suspend fun polyline(realtimeFeed: String, tripId: String): String? {
        if (!enabled || tripId.isBlank()) return null
        val key = bundleKeyFor(realtimeFeed) ?: return null
        val dataset = shapesDataset(key) ?: return null
        val shapeId = dataset.trip_index[tripId] ?: return null
        return dataset.shapes[shapeId]
    }

    private suspend fun directoryMap(): Map<String, TrackStop>? {
        directory?.takeIf { fresh(it) }?.let { return it.value }
        return mutex.withLock {
            directory?.takeIf { fresh(it) }?.let { return it.value }
            val loaded = runCatching { loadDirectory() }
                .onFailure { logger.warn("track datasets: directory load failed: {}", it.message) }
                .getOrNull()
            if (loaded != null) logger.info("✅ Track stop directory loaded: {} stops", loaded.size)
            directory = Loaded(loaded, clock())
            loaded
        }
    }

    private suspend fun shapesDataset(key: String): ShapesDataset? {
        shapesByKey[key]?.takeIf { fresh(it) }?.let { return it.value }
        return mutex.withLock {
            shapesByKey[key]?.takeIf { fresh(it) }?.let { return it.value }
            val loaded = runCatching { loadShapes(key) }
                .onFailure { logger.warn("track datasets: shapes_{} load failed: {}", key, it.message) }
                .getOrNull()
            if (loaded != null) {
                logger.info("✅ Shapes loaded for {}: {} shapes, {} trips", key, loaded.shapes.size, loaded.trip_index.size)
            }
            shapesByKey[key] = Loaded(loaded, clock())
            loaded
        }
    }

    /**
     * A successful load is fresh forever in dir mode; in manifest mode it is
     * fresh while the (recheck-cached) manifest still carries the version it
     * was loaded under. A failed load is fresh only for [failureRetryMillis].
     */
    private suspend fun fresh(loaded: Loaded<*>): Boolean {
        if (loaded.value == null) return clock() - loaded.at < failureRetryMillis
        if (!localDir.isNullOrBlank()) return true
        val current = currentManifest() ?: return true // can't re-check → keep serving what we have
        val loadedVersion = when (val v = loaded.value) {
            is ShapesDataset -> v.version
            is Map<*, *> -> directoryVersion
            else -> return true
        }
        return loadedVersion == current.version
    }

    @Volatile private var directoryVersion: String = ""

    private suspend fun loadDirectory(): Map<String, TrackStop> {
        val bytes = artifactBytes("track_stops.pb")
        val dataset = TrackStopsDataset.ADAPTER.decode(bytes)
        directoryVersion = dataset.version
        val map = HashMap<String, TrackStop>(dataset.stops.size * 2)
        dataset.stops.forEach { if (it.stop_id.isNotEmpty()) map[it.stop_id] = it }
        return map
    }

    private suspend fun loadShapes(key: String): ShapesDataset =
        ShapesDataset.ADAPTER.decode(artifactBytes("shapes_$key.pb"))

    private suspend fun artifactBytes(name: String): ByteArray {
        if (!localDir.isNullOrBlank()) {
            return runInterruptible(Dispatchers.IO) { File(localDir, name).readBytes() }
        }
        val m = currentManifest() ?: error("manifest unavailable")
        val artifact = m.artifacts.firstOrNull { it.name == name } ?: error("$name not in manifest")
        val bytes = runInterruptible(Dispatchers.IO) { fetcher(artifact.url) }
        val actual = sha256Hex(bytes)
        check(actual == artifact.sha256) { "$name sha256 mismatch: expected ${artifact.sha256}, got $actual" }
        return bytes
    }

    private suspend fun currentManifest(): TrackManifest? {
        manifest?.let { cached ->
            val ttl = if (cached.value == null) failureRetryMillis else recheckMillis
            if (clock() - cached.at < ttl) return cached.value
        }
        val url = manifestUrl ?: return null
        val fetched = runCatching {
            json.decodeFromString<TrackManifest>(
                runInterruptible(Dispatchers.IO) { fetcher(url) }.decodeToString(),
            )
        }.onFailure { logger.warn("track datasets: manifest fetch failed: {}", it.message) }.getOrNull()
        manifest = Loaded(fetched, clock())
        return fetched
    }

    @Serializable
    private data class TrackManifest(
        val version: String,
        val artifacts: List<Artifact>,
    ) {
        @Serializable
        data class Artifact(val name: String, val url: String, val sha256: String, val size_bytes: Long = 0)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Realtime feed name → static-bundle key (the `shapes_<key>.pb`
         * suffix, which is the GTFS zip basename in the dataset job). The
         * four light-rail realtime feeds share one static bundle. Buses have
         * no shapes dataset yet (10–50× larger; T3 scope).
         */
        fun bundleKeyFor(realtimeFeed: String): String? = when {
            realtimeFeed.startsWith("lightrail") -> "lightrail"
            realtimeFeed == "ferries/sydneyferries" -> "ferries_sydneyferries"
            realtimeFeed == "buses" -> null
            realtimeFeed.isBlank() -> null
            else -> realtimeFeed
        }

        private val httpClient: HttpClient by lazy {
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL) // GitHub release assets 302 to a CDN
                .build()
        }

        private fun httpGet(url: String): ByteArray {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            check(response.statusCode() in 200..299) { "GET $url -> ${response.statusCode()}" }
            return response.body()
        }

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
