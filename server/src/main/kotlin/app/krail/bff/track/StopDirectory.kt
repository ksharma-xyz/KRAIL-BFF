package app.krail.bff.track

import app.krail.kgtfs.proto.NswStopList
import org.slf4j.LoggerFactory

/**
 * In-memory stop_id → (name, lat, lon) lookup, loaded once (lazily) from
 * the bundled NSW stops dataset (`resources/dataset/nsw_stops.pb` — the
 * same artifact the KRAIL app bundles, currently v59).
 *
 * ~37k stops ≈ a few MB of heap. Refresh path: replace the resource when
 * the dataset pipeline publishes new versions (TODO: fetch from the
 * GitHub Releases manifest instead of bundling, same as the app will).
 */
class StopDirectory {
    private val logger = LoggerFactory.getLogger(StopDirectory::class.java)

    data class Stop(val name: String, val lat: Double, val lon: Double)

    private val byId: Map<String, Stop> by lazy {
        try {
            val bytes = checkNotNull(javaClass.getResourceAsStream("/dataset/nsw_stops.pb")) {
                "dataset/nsw_stops.pb missing from resources"
            }.readBytes()
            val list = NswStopList.ADAPTER.decode(bytes)
            val map = HashMap<String, Stop>(list.nswStops.size * 2)
            list.nswStops.forEach { s ->
                if (s.stopId.isNotEmpty()) map[s.stopId] = Stop(s.stopName, s.lat, s.lon)
            }
            logger.info("✅ Stop directory loaded: {} stops", map.size)
            map
        } catch (e: Throwable) {
            // Names are an enhancement — tracking must work without them.
            logger.error("Stop directory failed to load; stop names will be empty", e)
            emptyMap()
        }
    }

    fun find(stopId: String): Stop? = byId[stopId]
}
