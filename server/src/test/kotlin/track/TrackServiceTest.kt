package track

import app.krail.bff.client.nsw.NswClient
import app.krail.bff.client.nsw.NswUpstreamException
import app.krail.bff.proto.LegTracking
import app.krail.bff.proto.OccupancyInfo
import app.krail.bff.proto.StopProgress
import app.krail.bff.proto.TrackRequest
import app.krail.bff.track.TrackService
import com.codahale.metrics.MetricRegistry
import com.google.transit.realtime.FeedMessage
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drives TrackService with the captured real feeds (2026-06-12). The
 * fixtures' vehicles/timestamps are from capture time, so the test clock is
 * pinned near the feed header timestamp rather than using the wall clock.
 */
class TrackServiceTest {

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/gtfsrt/$name")) { "missing $name" }.readBytes()

    /** Feed header timestamp of the captured sydneytrains vehiclepos. */
    private val captureInstant: Instant by lazy {
        val feed = FeedMessage.ADAPTER.decode(fixture("vehiclepos_sydneytrains.pb"))
        Instant.ofEpochSecond(feed.header_.timestamp ?: 0L)
    }

    private val captureServiceDate: String by lazy {
        LocalDate.ofInstant(captureInstant, ZoneId.of("Australia/Sydney"))
            .format(DateTimeFormatter.BASIC_ISO_DATE)
    }

    /** A trip id present in BOTH captured sydneytrains feeds. */
    private val liveTripId: String by lazy {
        val vp = FeedMessage.ADAPTER.decode(fixture("vehiclepos_sydneytrains.pb"))
        val tu = FeedMessage.ADAPTER.decode(fixture("tripupdates_sydneytrains.pb"))
        val tuIds = tu.entity.mapNotNull { it.trip_update?.trip?.trip_id }.toSet()
        vp.entity.mapNotNull { it.vehicle?.trip?.trip_id }.first { it in tuIds }
    }

    private class FakeNsw(
        private val fixtures: (String) -> ByteArray,
        private val failFeeds: Boolean = false,
    ) : NswClient {
        var vehicleposFetches = 0
        override suspend fun healthCheck() = true
        override suspend fun getTrip(
            originStopId: String, destinationStopId: String, depArr: String,
            date: String?, time: String?, excludedModes: Set<Int>,
        ) = throw UnsupportedOperationException("unused")
        override suspend fun getTripProto(
            originStopId: String, destinationStopId: String, depArr: String,
            date: String?, time: String?, excludedModes: Set<Int>,
        ) = throw UnsupportedOperationException("unused")
        override suspend fun getTripRaw(
            originStopId: String, destinationStopId: String, depArr: String,
            date: String?, time: String?, excludedModes: Set<Int>,
        ) = throw UnsupportedOperationException("unused")
        override suspend fun getDeparturesRaw(stopId: String, date: String?, time: String?) =
            throw UnsupportedOperationException("unused")
        override suspend fun getCarparkRaw(facilityId: String?) =
            throw UnsupportedOperationException("unused")
        override suspend fun getGtfsRealtimeRaw(version: Int, feed: String): ByteArray {
            if (failFeeds) throw NswUpstreamException(503, "down", "")
            return when (feed) {
                "sydneytrains" -> fixtures("tripupdates_sydneytrains.pb")
                else -> throw NswUpstreamException(404, "no fixture for $feed", "")
            }
        }
        override suspend fun getVehiclePositionsRaw(feed: String, version: Int): ByteArray {
            if (failFeeds) throw NswUpstreamException(503, "down", "")
            vehicleposFetches++
            return when (feed) {
                "sydneytrains" -> fixtures("vehiclepos_sydneytrains.pb")
                "metro" -> fixtures("vehiclepos_metro.pb")
                "buses" -> fixtures("vehiclepos_buses.pb")
                else -> throw NswUpstreamException(404, "no fixture for $feed", "")
            }
        }
    }

    private fun fakeNsw(failFeeds: Boolean = false) = FakeNsw(::fixture, failFeeds)

    private fun service(nsw: NswClient = fakeNsw()) = TrackService(
        nsw = nsw,
        metrics = MetricRegistry(),
        clock = { captureInstant },
    )

    private fun leg(tripId: String, productClass: Int = 1, serviceDate: String = captureServiceDate) =
        TrackRequest.TrackLeg(
            leg_ref = "leg-1",
            realtime_trip_id = tripId,
            product_class = productClass,
            origin_stop_id = "200060",
            destination_stop_id = "2135110",
            service_date = serviceDate,
        )

    @Test
    fun `live train resolves to TRACKING with vehicle, fleet and stops`() = runBlocking {
        val response = service().snapshot(TrackRequest(legs = listOf(leg(liveTripId))))
        val tracked = response.legs.single()

        assertEquals(LegTracking.Status.TRACKING, tracked.status)
        val vehicle = assertNotNull(tracked.vehicle)
        assertTrue(vehicle.latitude < -30 && vehicle.longitude > 140, "position in NSW")
        assertTrue(vehicle.measured_at_epoch_sec > 0, "staleness timestamp always set")

        val fleet = assertNotNull(tracked.fleet, "dotted trip id must yield fleet info")
        assertTrue(fleet.display_name.isNotBlank())

        assertTrue(tracked.stops.isNotEmpty(), "trip updates exist for this trip")
        // NSW trains publish delay-style updates; absolute times appear on a
        // subset of stops. Either live signal must surface.
        assertTrue(
            tracked.has_delay || tracked.stops.any { it.estimated_epoch_sec > 0 },
            "expected a live time signal (delay or estimated times)",
        )
        assertTrue(response.suggested_poll_seconds > 0)
    }

    @Test
    fun `per-carriage occupancy flows through when the feed has it`() = runBlocking {
        // Find a vehicle with carriage data and occupancy in the fixture.
        val vp = FeedMessage.ADAPTER.decode(fixture("vehiclepos_sydneytrains.pb"))
        val tripWithCars = vp.entity.mapNotNull { it.vehicle }
            .first { v -> v.carriage_sequence.any { it.occupancy_status != null } }
            .trip!!.trip_id!!

        val tracked = service().snapshot(TrackRequest(legs = listOf(leg(tripWithCars)))).legs.single()
        val occupancy = assertNotNull(tracked.occupancy)
        assertTrue(occupancy.cars.isNotEmpty(), "expected per-carriage occupancy")
        assertTrue(occupancy.cars.all { it.sequence >= 1 }, "sequence is 1-based")
        assertTrue(occupancy.cars.any { it.level != OccupancyInfo.Level.LEVEL_UNSPECIFIED })
    }

    @Test
    fun `unknown trip id on a running service resolves to NO_REALTIME`() = runBlocking {
        val tracked = service()
            .snapshot(TrackRequest(legs = listOf(leg("999Z.0000.000.00.A.8.00000000"))))
            .legs.single()
        assertEquals(LegTracking.Status.NO_REALTIME, tracked.status)
    }

    @Test
    fun `past service date resolves to EXPIRED without touching feeds`() = runBlocking {
        val tracked = service(fakeNsw(failFeeds = true))
            .snapshot(TrackRequest(legs = listOf(leg(liveTripId, serviceDate = "20200101"))))
            .legs.single()
        assertEquals(LegTracking.Status.EXPIRED, tracked.status)
    }

    @Test
    fun `upstream failure resolves to UPSTREAM_UNAVAILABLE, not an exception`() = runBlocking {
        val tracked = service(fakeNsw(failFeeds = true))
            .snapshot(TrackRequest(legs = listOf(leg(liveTripId))))
            .legs.single()
        assertEquals(LegTracking.Status.UPSTREAM_UNAVAILABLE, tracked.status)
    }

    @Test
    fun `one bad leg never poisons the others`() = runBlocking {
        val legs = listOf(
            leg(liveTripId),
            TrackRequest.TrackLeg(
                leg_ref = "leg-2",
                realtime_trip_id = "no-such-trip",
                product_class = 9, // ferries — no fixture, fetch fails
                origin_stop_id = "1",
                destination_stop_id = "2",
                service_date = captureServiceDate,
            ),
        )
        val response = service().snapshot(TrackRequest(legs = legs))
        assertEquals(2, response.legs.size)
        assertEquals(LegTracking.Status.TRACKING, response.legs[0].status)
        assertEquals(LegTracking.Status.UPSTREAM_UNAVAILABLE, response.legs[1].status)
    }

    @Test
    fun `feed cache collapses repeated requests into one upstream fetch`() = runBlocking {
        val nsw = fakeNsw()
        val svc = service(nsw)
        repeat(5) { svc.snapshot(TrackRequest(legs = listOf(leg(liveTripId)))) }
        // One vehiclepos fetch despite five polls (TTL 15s, fixed clock).
        assertEquals(1, nsw.vehicleposFetches)
    }

    @Test
    fun `stops are tagged relative to the requested journey segment`() = runBlocking {
        // Take a live trip's real stop ids and request a mid-trip segment.
        val tu = FeedMessage.ADAPTER.decode(fixture("tripupdates_sydneytrains.pb"))
        val fullStops = tu.entity.first { it.trip_update?.trip?.trip_id == liveTripId }
            .trip_update!!.stop_time_update.mapNotNull { it.stop_id }
        check(fullStops.size >= 4) { "fixture trip too short for segment test" }
        val origin = fullStops[1]
        val destination = fullStops[fullStops.size - 2]

        val stops = service().snapshot(TrackRequest(legs = listOf(
            leg(liveTripId).copy(origin_stop_id = origin, destination_stop_id = destination),
        ))).legs.single().stops
        // Full run still present — clients choose what to render.
        assertEquals(fullStops.size, stops.size)
        assertEquals(StopProgress.Segment.BEFORE_JOURNEY, stops.first().segment)
        assertEquals(StopProgress.Segment.AFTER_JOURNEY, stops.last().segment)
        val journey = stops.filter { it.segment == StopProgress.Segment.JOURNEY }
        assertEquals(origin, journey.first().stop_id)
        assertEquals(destination, journey.last().stop_id)
        assertEquals(fullStops.size - 2, journey.size)

        // Unknown endpoints → everything UNSPECIFIED, never a wrong tag.
        val untagged = service().snapshot(TrackRequest(legs = listOf(
            leg(liveTripId).copy(origin_stop_id = "NOPE1", destination_stop_id = "NOPE2"),
        ))).legs.single().stops
        assertTrue(untagged.all { it.segment == StopProgress.Segment.SEGMENT_UNSPECIFIED })
    }

    @Test
    fun `stop states are consistent with vehicle progress`() = runBlocking {
        val tracked = service().snapshot(TrackRequest(legs = listOf(leg(liveTripId)))).legs.single()
        val states = tracked.stops.map { it.state }
        // DEPARTED stops never appear after UPCOMING ones.
        val firstUpcoming = states.indexOfFirst { it == StopProgress.State.UPCOMING }
        if (firstUpcoming >= 0) {
            assertTrue(
                states.drop(firstUpcoming).none { it == StopProgress.State.DEPARTED },
                "departed stop after an upcoming stop: $states",
            )
        }
    }
}
