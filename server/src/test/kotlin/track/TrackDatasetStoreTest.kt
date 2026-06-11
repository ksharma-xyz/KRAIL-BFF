package track

import app.krail.bff.track.TrackDatasetStore
import app.krail.bff.trackdata.ShapesDataset
import app.krail.bff.trackdata.TrackStop
import app.krail.bff.trackdata.TrackStopsDataset
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackDatasetStoreTest {

    private fun directoryBytes(version: String = "20260611") = TrackStopsDataset(
        version = version,
        stops = listOf(
            TrackStop(stop_id = "2000336", name = "Central Station Platform 16", parent_id = "200060", lat = -33.8842, lon = 151.2062),
        ),
    ).encode()

    private fun shapesBytes(version: String = "20260611", polyline: String = "poly-v1") = ShapesDataset(
        version = version,
        feed = "sydneytrains",
        shapes = mapOf("shape-1" to polyline),
        trip_index = mapOf("trip-a" to "shape-1"),
    ).encode()

    private fun tempDataDir(): File {
        val dir = File.createTempFile("trackds", "").apply { delete(); mkdirs() }
        File(dir, "track_stops.pb").writeBytes(directoryBytes())
        File(dir, "shapes_sydneytrains.pb").writeBytes(shapesBytes())
        return dir
    }

    @Test
    fun `local dir mode resolves platform stops and trip polylines`() = runBlocking {
        val store = TrackDatasetStore(localDir = tempDataDir().absolutePath, manifestUrl = null)
        assertEquals("Central Station Platform 16", store.stop("2000336")?.name)
        assertEquals("200060", store.stop("2000336")?.parent_id)
        assertNull(store.stop("999999"), "unknown id never guesses")
        assertEquals("poly-v1", store.polyline("sydneytrains", "trip-a"))
        assertNull(store.polyline("sydneytrains", "trip-unknown"))
        assertNull(store.polyline("buses", "trip-a"), "no bus shapes dataset yet")
    }

    @Test
    fun `unconfigured store answers null without touching anything`() = runBlocking {
        val store = TrackDatasetStore(
            localDir = null, manifestUrl = null,
            fetcher = { error("must not fetch") },
        )
        assertNull(store.stop("2000336"))
        assertNull(store.polyline("sydneytrains", "trip-a"))
    }

    @Test
    fun `light rail realtime feeds map onto the single lightrail bundle`() {
        assertEquals("lightrail", TrackDatasetStore.bundleKeyFor("lightrail/innerwest"))
        assertEquals("lightrail", TrackDatasetStore.bundleKeyFor("lightrail/parramatta"))
        assertEquals("ferries_sydneyferries", TrackDatasetStore.bundleKeyFor("ferries/sydneyferries"))
        assertEquals("sydneytrains", TrackDatasetStore.bundleKeyFor("sydneytrains"))
        assertNull(TrackDatasetStore.bundleKeyFor("buses"))
    }

    @Test
    fun `manifest mode fetches, verifies sha256 and hot-swaps on version bump`() = runBlocking {
        var now = 0L
        var version = "20260611"
        var shapes = shapesBytes(version, polyline = "poly-v1")

        fun manifestJson(): String {
            val sha = MessageDigest.getInstance("SHA-256").digest(shapes).joinToString("") { "%02x".format(it) }
            return """{"version":"$version","artifacts":[
                {"name":"shapes_sydneytrains.pb","url":"https://x/shapes_sydneytrains.pb","sha256":"$sha","size_bytes":${shapes.size}}
            ]}"""
        }

        val store = TrackDatasetStore(
            localDir = null,
            manifestUrl = "https://x/track_manifest.json",
            recheckMillis = 1000,
            clock = { now },
            fetcher = { url ->
                when {
                    url.endsWith("track_manifest.json") -> manifestJson().toByteArray()
                    url.endsWith("shapes_sydneytrains.pb") -> shapes
                    else -> error("unexpected $url")
                }
            },
        )

        assertEquals("poly-v1", store.polyline("sydneytrains", "trip-a"))

        // New weekly release: version + bytes change; before the recheck TTL
        // the old data keeps serving, after it the store hot-swaps.
        version = "20260618"
        shapes = shapesBytes(version, polyline = "poly-v2")
        assertEquals("poly-v1", store.polyline("sydneytrains", "trip-a"), "served from cache inside TTL")
        now += 1001
        assertEquals("poly-v2", store.polyline("sydneytrains", "trip-a"), "hot-swapped after manifest recheck")
    }

    @Test
    fun `sha256 mismatch rejects the artifact instead of serving it`() = runBlocking {
        val shapes = shapesBytes()
        val store = TrackDatasetStore(
            localDir = null,
            manifestUrl = "https://x/track_manifest.json",
            fetcher = { url ->
                when {
                    url.endsWith("track_manifest.json") ->
                        """{"version":"1","artifacts":[{"name":"shapes_sydneytrains.pb","url":"https://x/shapes_sydneytrains.pb","sha256":"deadbeef","size_bytes":1}]}""".toByteArray()
                    else -> shapes
                }
            },
        )
        assertNull(store.polyline("sydneytrains", "trip-a"))
    }
}
