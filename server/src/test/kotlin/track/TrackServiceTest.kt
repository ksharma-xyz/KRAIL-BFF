package track

import app.krail.bff.client.nsw.NswClient
import app.krail.bff.client.nsw.NswUpstreamException
import app.krail.bff.model.TripResponse
import app.krail.bff.proto.LegTracking
import app.krail.bff.proto.OccupancyInfo
import app.krail.bff.proto.StopProgress
import app.krail.bff.proto.TrackRequest
import app.krail.bff.proto.LegGeometry
import app.krail.bff.track.TrackDatasetStore
import app.krail.bff.track.TrackService
import app.krail.bff.track.TripOccupancyEnricher
import app.krail.bff.trackdata.ShapesDataset
import app.krail.bff.trackdata.TrackStop
import app.krail.bff.trackdata.TrackStopsDataset
import com.codahale.metrics.MetricRegistry
import com.google.transit.realtime.FeedMessage
import kotlinx.coroutines.runBlocking
import java.io.File
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
        var tripUpdatesOverride: ByteArray? = null
        var tripResponse: TripResponse? = null
        var tripCalls = 0
        override suspend fun healthCheck() = true
        override suspend fun getTrip(
            originStopId: String, destinationStopId: String, depArr: String,
            date: String?, time: String?, excludedModes: Set<Int>,
        ): TripResponse {
            tripCalls++
            return tripResponse ?: throw UnsupportedOperationException("unused")
        }
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
                "sydneytrains" -> tripUpdatesOverride ?: fixtures("tripupdates_sydneytrains.pb")
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

    private fun service(
        nsw: NswClient = fakeNsw(),
        datasets: TrackDatasetStore = TrackDatasetStore(null, null),
        enricher: TripOccupancyEnricher? = null,
    ) = TrackService(
        nsw = nsw,
        metrics = MetricRegistry(),
        datasets = datasets,
        occupancyEnricher = enricher,
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
        // Note: train stops in GTFS-R are PLATFORM ids; the bundled search
        // dataset only carries parents + bus stops, so train stop names may
        // be empty until the T1.5 platform-level directory lands.
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
    fun `journey ends when the destination is behind the vehicle`() = runBlocking {
        // Find a live trip whose timeline has at least one DEPARTED stop,
        // then request a journey that ends at that already-passed stop.
        val svc = service()
        val full = svc.snapshot(TrackRequest(legs = listOf(leg(liveTripId)))).legs.single()
        val departed = full.stops.filter { it.state == StopProgress.State.DEPARTED }
        if (departed.size < 2) return@runBlocking // fixture trip just started; nothing to assert
        val origin = departed.first().stop_id
        val dest = departed.last().stop_id

        val ended = svc.snapshot(TrackRequest(legs = listOf(
            leg(liveTripId).copy(origin_stop_id = origin, destination_stop_id = dest),
        ))).legs.single()
        assertEquals(LegTracking.Status.ENDED, ended.status)
        // No live vehicle for a journey the user has finished.
        assertEquals(null, ended.vehicle)
        assertEquals(null, ended.occupancy)
    }

    @Test
    fun `trip whose segment was trimmed from the feed resolves to ENDED`() = runBlocking {
        // Live trip, but the user's origin AND destination are no longer in
        // the remaining-stops list and departure was 30 min ago — the
        // user's segment is behind the vehicle (NSW trims passed stops).
        val tracked = service().snapshot(TrackRequest(legs = listOf(
            leg(liveTripId).copy(
                origin_stop_id = "NOPE1",
                destination_stop_id = "NOPE2",
                planned_departure_utc = captureInstant.minusSeconds(30 * 60).toString(),
            ),
        ))).legs.single()
        assertEquals(LegTracking.Status.ENDED, tracked.status)
        assertEquals(null, tracked.vehicle)
    }

    @Test
    fun `vanished trip with long-past departure resolves to ENDED`() = runBlocking {
        val tracked = service().snapshot(TrackRequest(legs = listOf(
            leg("999Z.0000.000.00.A.8.00000000").copy(
                planned_departure_utc = captureInstant.minusSeconds(5 * 3600).toString(),
            ),
        ))).legs.single()
        assertEquals(LegTracking.Status.ENDED, tracked.status)
    }

    @Test
    fun `snapshot stays complete after NSW trims passed stops from the feed`() = runBlocking {
        val nsw = fakeNsw()
        // ttl 0 → every poll refetches, so the override takes effect.
        val svc = TrackService(
            nsw = nsw, metrics = MetricRegistry(),
            vehicleposTtlMillis = 0, tripUpdatesTtlMillis = 0,
            clock = { captureInstant },
        )

        val first = svc.snapshot(TrackRequest(legs = listOf(leg(liveTripId)))).legs.single()
        val fullIds = first.stops.map { it.stop_id }
        check(fullIds.size >= 3)

        // Simulate NSW trimming the first two (now passed) stops.
        val feed = FeedMessage.ADAPTER.decode(fixture("tripupdates_sydneytrains.pb"))
        val trimmedEntities = feed.entity.map { e ->
            if (e.trip_update?.trip?.trip_id == liveTripId) {
                e.copy(trip_update = e.trip_update!!.copy(
                    stop_time_update = e.trip_update!!.stop_time_update.drop(2),
                ))
            } else e
        }
        nsw.tripUpdatesOverride = FeedMessage.ADAPTER.encode(feed.copy(entity = trimmedEntities))

        val second = svc.snapshot(TrackRequest(legs = listOf(leg(liveTripId)))).legs.single()
        // Server memory re-attaches the trimmed stops as DEPARTED.
        assertEquals(fullIds, second.stops.map { it.stop_id }, "snapshot must remain the complete run")
        assertEquals(StopProgress.State.DEPARTED, second.stops[0].state)
        assertEquals(StopProgress.State.DEPARTED, second.stops[1].state)
    }

    /** Stop ids of the live trip, from the captured TripUpdates fixture. */
    private val liveTripStopIds: List<String> by lazy {
        val tu = FeedMessage.ADAPTER.decode(fixture("tripupdates_sydneytrains.pb"))
        tu.entity.first { it.trip_update?.trip?.trip_id == liveTripId }
            .trip_update!!.stop_time_update.mapNotNull { it.stop_id }
    }

    /** T1.5 datasets on disk: platform names for the trip's stops (+ a shape when [withShape]). */
    private fun datasetDir(withShape: Boolean): TrackDatasetStore {
        val dir = File.createTempFile("trackds", "").apply { delete(); mkdirs() }
        File(dir, "track_stops.pb").writeBytes(
            TrackStopsDataset(
                version = "test",
                stops = liveTripStopIds.mapIndexed { i, id ->
                    TrackStop(stop_id = id, name = "Platform $i", lat = -33.8 - i * 0.01, lon = 151.2 + i * 0.01)
                },
            ).encode(),
        )
        if (withShape) {
            File(dir, "shapes_sydneytrains.pb").writeBytes(
                ShapesDataset(
                    version = "test",
                    feed = "sydneytrains",
                    shapes = mapOf("shape-1" to "encoded-test-polyline"),
                    trip_index = mapOf(liveTripId to "shape-1"),
                ).encode(),
            )
        }
        return TrackDatasetStore(localDir = dir.absolutePath, manifestUrl = null)
    }

    @Test
    fun `first poll with geometry gets the shapes polyline and platform names`() = runBlocking {
        val tracked = service(datasets = datasetDir(withShape = true))
            .snapshot(TrackRequest(legs = listOf(leg(liveTripId)), include_geometry = true))
            .legs.single()

        val geometry = assertNotNull(tracked.geometry)
        assertEquals(LegGeometry.Source.GTFS_SHAPES, geometry.source)
        assertEquals("encoded-test-polyline", geometry.encoded_polyline)
        // T1.5 directory names train PLATFORM ids the search dataset lacks.
        assertTrue(tracked.stops.all { it.stop_name.isNotBlank() }, "every stop named")
        assertTrue(tracked.stops.all { it.latitude != 0.0 && it.longitude != 0.0 }, "pins ship with geometry")
    }

    @Test
    fun `shape miss falls back to straight lines through stop coordinates`() = runBlocking {
        val tracked = service(datasets = datasetDir(withShape = false))
            .snapshot(TrackRequest(legs = listOf(leg(liveTripId)), include_geometry = true))
            .legs.single()

        val geometry = assertNotNull(tracked.geometry, "fallback must still draw a line")
        assertEquals(LegGeometry.Source.STOP_STRAIGHT_LINES, geometry.source)
        assertTrue(geometry.encoded_polyline.isNotBlank())
    }

    @Test
    fun `steady-state poll carries no geometry and no coordinates`() = runBlocking {
        val tracked = service(datasets = datasetDir(withShape = true))
            .snapshot(TrackRequest(legs = listOf(leg(liveTripId)))) // include_geometry defaults false
            .legs.single()

        assertEquals(null, tracked.geometry)
        assertTrue(tracked.stops.all { it.latitude == 0.0 && it.longitude == 0.0 })
        assertTrue(tracked.stops.all { it.stop_name.isNotBlank() }, "names still ship every poll")
    }

    /** Trip Planner response whose leg runs [tripId] with per-stop occupancy. */
    private fun tripResponseFor(tripId: String, occupancy: String = "MANY_SEATS") = TripResponse(
        journeys = listOf(
            TripResponse.Journey(
                legs = listOf(
                    TripResponse.Leg(
                        transportation = TripResponse.Transportation(
                            properties = TripResponse.TransportationProperties(realtimeTripId = tripId),
                        ),
                        stopSequence = liveTripStopIds.map { id ->
                            TripResponse.StopSequence(
                                id = id,
                                properties = TripResponse.DestinationProperties(occupancy = occupancy),
                            )
                        },
                    ),
                ),
            ),
        ),
    )

    private fun enricher(nsw: NswClient) = TripOccupancyEnricher(nsw = nsw, metrics = MetricRegistry())

    @Test
    fun `expected occupancy ships with geometry when Trip Planner confirms the locked trip`() = runBlocking {
        val nsw = fakeNsw().apply { tripResponse = tripResponseFor(liveTripId) }
        val svc = service(nsw = nsw, enricher = enricher(nsw))
        val withDeparture = leg(liveTripId).copy(planned_departure_utc = captureInstant.toString())

        val first = svc.snapshot(TrackRequest(legs = listOf(withDeparture), include_geometry = true)).legs.single()
        assertTrue(
            first.stops.all { it.expected_occupancy == OccupancyInfo.Level.MANY_SEATS_AVAILABLE },
            "every stop carries the forecast",
        )
        assertEquals(1, nsw.tripCalls)

        // Cached per (trip_id, service_date): a second first-poll costs nothing.
        svc.snapshot(TrackRequest(legs = listOf(withDeparture), include_geometry = true))
        assertEquals(1, nsw.tripCalls, "one Trip Planner call per tracked trip")

        // Steady-state polls never trigger the enrichment.
        val steady = svc.snapshot(TrackRequest(legs = listOf(withDeparture))).legs.single()
        assertTrue(steady.stops.all { it.expected_occupancy == OccupancyInfo.Level.LEVEL_UNSPECIFIED })
        assertEquals(1, nsw.tripCalls)
    }

    @Test
    fun `re-planned Trip Planner response is discarded, never substituted`() = runBlocking {
        val nsw = fakeNsw().apply { tripResponse = tripResponseFor("some-OTHER-trip") }
        val svc = service(nsw = nsw, enricher = enricher(nsw))

        val tracked = svc.snapshot(
            TrackRequest(legs = listOf(leg(liveTripId)), include_geometry = true),
        ).legs.single()
        assertTrue(
            tracked.stops.all { it.expected_occupancy == OccupancyInfo.Level.LEVEL_UNSPECIFIED },
            "occupancy from a different trip must never leak in",
        )
    }

    @Test
    fun `Trip Planner failure costs nothing — tracking is unaffected`() = runBlocking {
        val nsw = fakeNsw() // tripResponse null → getTrip throws
        val svc = service(nsw = nsw, enricher = enricher(nsw))
        val tracked = svc.snapshot(
            TrackRequest(legs = listOf(leg(liveTripId)), include_geometry = true),
        ).legs.single()
        assertEquals(LegTracking.Status.TRACKING, tracked.status)
        assertTrue(tracked.stops.all { it.expected_occupancy == OccupancyInfo.Level.LEVEL_UNSPECIFIED })
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
