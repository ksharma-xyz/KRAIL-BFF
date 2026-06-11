package proto

import com.google.transit.realtime.FeedMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Decodes captured real NSW feeds (server/src/test/resources/gtfsrt/,
 * fetched 2026-06-12) and pins the assumptions the tracking feature
 * depends on. If TfNSW changes feed shapes, these fail first.
 */
class GtfsRealtimeDecodeTest {

    private fun load(name: String): FeedMessage {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/gtfsrt/$name")) {
            "missing fixture $name"
        }.readBytes()
        return FeedMessage.ADAPTER.decode(bytes)
    }

    @Test
    fun `sydneytrains vehiclepos decodes with dotted trip ids`() {
        val feed = load("vehiclepos_sydneytrains.pb")
        assertTrue(feed.entity.isNotEmpty(), "feed should contain vehicles")
        val withTrip = feed.entity.mapNotNull { it.vehicle?.trip?.trip_id }
        assertTrue(withTrip.isNotEmpty())
        // Dotted format: name.timetable.version.dop.setType.cars.instance
        val dotted = withTrip.count { it.split(".").size == 7 }
        assertTrue(dotted > withTrip.size / 2, "expected mostly dotted trip ids, got $dotted/${withTrip.size}")
    }

    @Test
    fun `sydneytrains vehiclepos carries positions and timestamps`() {
        val feed = load("vehiclepos_sydneytrains.pb")
        val v = feed.entity.firstNotNullOf { it.vehicle }
        assertNotNull(v.position)
        assertNotNull(v.timestamp)
        assertTrue(v.position!!.latitude < -30f && v.position!!.latitude > -38f, "NSW latitude")
        assertTrue(v.position!!.longitude > 140f && v.position!!.longitude < 155f, "NSW longitude")
    }

    @Test
    fun `metro vehiclepos exposes per-carriage extension data`() {
        val feed = load("vehiclepos_metro.pb")
        val withCars = feed.entity.mapNotNull { it.vehicle }.filter { it.carriage_sequence.isNotEmpty() }
        assertTrue(withCars.isNotEmpty(), "metro should report carriages via ext 1007")
        val cars = withCars.first().carriage_sequence
        assertEquals(6, cars.size, "metro sets are 6-car")
        assertTrue(cars.any { it.occupancy_status != null }, "carriages should carry occupancy")
        assertTrue(cars.any { !it.name.isNullOrBlank() }, "metro carriages are labelled")
    }

    @Test
    fun `metro vehiclepos exposes live vehicle model`() {
        val feed = load("vehiclepos_metro.pb")
        val models = feed.entity.mapNotNull { it.vehicle?.vehicle?.tfnsw_vehicle_descriptor?.vehicle_model }
        assertTrue(models.isNotEmpty(), "metro should report vehicle_model via ext 1007")
        assertTrue(models.first().isNotBlank())
    }

    @Test
    fun `sydneytrains vehiclepos exposes carriage occupancy`() {
        val feed = load("vehiclepos_sydneytrains.pb")
        val withCars = feed.entity.mapNotNull { it.vehicle }.filter { it.carriage_sequence.isNotEmpty() }
        assertTrue(withCars.isNotEmpty(), "sydneytrains should report carriages via ext 1007")
        val occupied = withCars.flatMap { it.carriage_sequence }.count { it.occupancy_status != null }
        assertTrue(occupied > 0, "per-carriage occupancy should be populated")
    }

    @Test
    fun `buses vehiclepos carries vehicle-level occupancy and bearing`() {
        val feed = load("vehiclepos_buses.pb")
        val vehicles = feed.entity.mapNotNull { it.vehicle }
        assertTrue(vehicles.isNotEmpty())
        assertTrue(vehicles.count { it.occupancy_status != null } > vehicles.size / 2)
        assertTrue(vehicles.count { it.position?.bearing != null } > vehicles.size / 2)
    }

    @Test
    fun `sydneytrains tripupdates carry stop time updates`() {
        val feed = load("tripupdates_sydneytrains.pb")
        val updates = feed.entity.mapNotNull { it.trip_update }
        assertTrue(updates.isNotEmpty())
        val all = updates.flatMap { it.stop_time_update }
        assertTrue(all.isNotEmpty())
        // NSW trains publish delay-style events; absolute times appear on a
        // subset. Either is enough to compute estimated stop times.
        assertTrue(all.any { it.arrival?.delay != null || it.departure?.delay != null })
        assertTrue(all.any { it.arrival?.time != null || it.departure?.time != null })
        assertTrue(all.any { !it.stop_id.isNullOrBlank() })
    }
}
