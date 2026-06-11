package app.krail.bff.track

import app.krail.bff.client.nsw.NswClient
import app.krail.bff.proto.FleetInfo
import app.krail.bff.proto.LegGeometry
import app.krail.bff.proto.LegTracking
import app.krail.bff.proto.OccupancyInfo
import app.krail.bff.proto.StopProgress
import app.krail.bff.proto.TrackRequest
import app.krail.bff.proto.TrackResponse
import app.krail.bff.proto.VehicleLive
import com.codahale.metrics.MetricRegistry
import com.google.transit.realtime.FeedMessage
import com.google.transit.realtime.TripUpdate
import com.google.transit.realtime.VehiclePosition
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Assembles a [TrackResponse] for the legs the client is tracking.
 *
 * Hard rule from TRACKING_DESIGN.md: tracking never re-plans. Everything
 * here derives from GTFS-Realtime feeds keyed by the locked trip_id.
 * Each leg resolves independently — one upstream failure marks that leg
 * UPSTREAM_UNAVAILABLE, it never fails the request.
 */
class TrackService(
    private val nsw: NswClient,
    private val metrics: MetricRegistry,
    private val stops: StopDirectory = StopDirectory(),
    private val datasets: TrackDatasetStore = TrackDatasetStore(),
    private val feedCache: FeedCache = FeedCache(),
    private val stopMemory: TripStopMemory = TripStopMemory(),
    private val vehicleposTtlMillis: Long = 15_000,
    private val tripUpdatesTtlMillis: Long = 30_000,
    private val suggestedPollSeconds: Int = 30,
    private val clock: () -> Instant = Instant::now,
) {
    private val logger = LoggerFactory.getLogger(TrackService::class.java)
    private val sydney = ZoneId.of("Australia/Sydney")
    private val serviceDateFormat = DateTimeFormatter.BASIC_ISO_DATE

    suspend fun snapshot(request: TrackRequest): TrackResponse {
        val now = clock()
        val legs = request.legs.map { leg ->
            try {
                resolveLeg(leg, now, request.include_geometry)
            } catch (e: Throwable) {
                // Defensive backstop — resolveLeg handles expected failures
                // itself; anything escaping is a bug, not a user error.
                logger.error("track: unexpected failure resolving leg {}", leg.leg_ref, e)
                metrics.counter("track.leg.unexpected_error").inc()
                LegTracking(leg_ref = leg.leg_ref, status = LegTracking.Status.UPSTREAM_UNAVAILABLE)
            }
        }
        return TrackResponse(
            fetched_at_epoch_sec = now.epochSecond,
            suggested_poll_seconds = suggestedPollSeconds,
            legs = legs,
        )
    }

    private suspend fun resolveLeg(leg: TrackRequest.TrackLeg, now: Instant, includeGeometry: Boolean): LegTracking {
        // Expiry: trip ids are scoped to a service day (Sydney time).
        val serviceDate = runCatching { LocalDate.parse(leg.service_date, serviceDateFormat) }.getOrNull()
        if (serviceDate != null && serviceDate.isBefore(LocalDate.now(sydney).minusDays(1))) {
            // minusDays(1): overnight services keep yesterday's service date
            // while still running after midnight.
            metrics.counter("track.status.expired").inc()
            return LegTracking(leg_ref = leg.leg_ref, status = LegTracking.Status.EXPIRED)
        }

        val feeds = FeedRegistry.feedsFor(leg.product_class)
        if (feeds.isEmpty()) {
            metrics.counter("track.status.no_feed").inc()
            return LegTracking(leg_ref = leg.leg_ref, status = LegTracking.Status.NO_REALTIME)
        }

        val tripId = leg.realtime_trip_id.trim()
        var anyFeedSucceeded = false
        var vehicle: VehiclePosition? = null
        var tripUpdate: TripUpdate? = null

        for (ref in feeds) {
            if (vehicle == null) {
                try {
                    val vp = feedCache.get("vp:${ref.feed}", vehicleposTtlMillis) {
                        nsw.getVehiclePositionsRaw(feed = ref.feed, version = ref.vehicleposVersion)
                    }
                    anyFeedSucceeded = true
                    vehicle = findVehicle(vp, tripId)
                } catch (e: Throwable) {
                    logger.warn("track: vehiclepos fetch failed for {}: {}", ref.feed, e.message)
                }
            }
            if (tripUpdate == null) {
                try {
                    val tu = feedCache.get("tu:${ref.feed}", tripUpdatesTtlMillis) {
                        nsw.getGtfsRealtimeRaw(version = ref.tripUpdatesVersion, feed = ref.feed)
                    }
                    anyFeedSucceeded = true
                    tripUpdate = findTripUpdate(tu, tripId)
                } catch (e: Throwable) {
                    logger.warn("track: tripupdates fetch failed for {}: {}", ref.feed, e.message)
                }
            }
            if (vehicle != null && tripUpdate != null) break
        }

        if (vehicle == null && tripUpdate == null) {
            // "Couldn't determine" only when every source errored; a feed
            // that answered without our trip is a real NO_REALTIME signal.
            return when {
                !anyFeedSucceeded -> {
                    metrics.counter("track.status.upstream_unavailable").inc()
                    LegTracking(leg_ref = leg.leg_ref, status = LegTracking.Status.UPSTREAM_UNAVAILABLE)
                }
                isPlannedDepartureInFuture(leg, now) -> {
                    metrics.counter("track.status.not_started").inc()
                    LegTracking(leg_ref = leg.leg_ref, status = LegTracking.Status.NOT_STARTED)
                }
                // Finished trips drop out of the feeds entirely. A client
                // returning from hours in the background must see a clean
                // "completed", not a puzzling NO_REALTIME: departure more
                // than 3h ago (longer than any Sydney metro-area run) and
                // the trip gone from feeds ⇒ it ran and finished.
                isPlannedDepartureOlderThan(leg, now, seconds = 3 * 3600) -> {
                    metrics.counter("track.status.ended").inc()
                    LegTracking(leg_ref = leg.leg_ref, status = LegTracking.Status.ENDED)
                }
                else -> {
                    metrics.counter("track.status.no_realtime").inc()
                    LegTracking(leg_ref = leg.leg_ref, status = LegTracking.Status.NO_REALTIME)
                }
            }
        }
        metrics.counter("track.match.exact").inc()

        val stops = buildStopTimeline(tripUpdate, vehicle, now, tripKey = "$tripId@${leg.service_date}", includeGeometry)
            .annotateSegments(leg.origin_stop_id, leg.destination_stop_id)

        // The USER's journey ends at their destination, not the vehicle's
        // terminus. Two ways to know it's over while the vehicle runs on:
        //  (a) destination matched and every JOURNEY stop departed;
        //  (b) NSW trims PASSED stops from TripUpdates, so when BOTH the
        //      origin and destination ids are missing from the remaining
        //      stops and departure is >10 min past, the user's segment is
        //      behind the vehicle. (Origin still present ⇒ trip hasn't
        //      reached the user yet — never complete.)
        val segmentMatched = stops.any { it.segment != StopProgress.Segment.SEGMENT_UNSPECIFIED }
        val journeyComplete = when {
            segmentMatched ->
                stops.none { it.segment == StopProgress.Segment.JOURNEY && it.state != StopProgress.State.DEPARTED }
            leg.origin_stop_id.isNotBlank() && leg.destination_stop_id.isNotBlank() && stops.isNotEmpty() ->
                stops.none { it.stop_id == leg.origin_stop_id || it.stop_id == leg.destination_stop_id } &&
                    isPlannedDepartureOlderThan(leg, now, seconds = 10 * 60).also {
                        if (it) metrics.counter("track.ended.by_trimmed_segment").inc()
                    }
            else -> false
        }

        val status = when {
            journeyComplete -> LegTracking.Status.ENDED
            vehicle != null -> LegTracking.Status.TRACKING
            stops.isNotEmpty() && stops.all { it.state == StopProgress.State.DEPARTED } ->
                LegTracking.Status.ENDED
            else -> LegTracking.Status.NOT_STARTED
        }
        metrics.counter("track.status.${status.name.lowercase()}").inc()

        if (status == LegTracking.Status.ENDED) {
            // Don't show the user a live vehicle they've already left.
            return LegTracking(leg_ref = leg.leg_ref, status = status, stops = stops)
        }

        val delay = latestDelaySeconds(tripUpdate)
        return LegTracking(
            leg_ref = leg.leg_ref,
            status = status,
            vehicle = vehicle?.let { mapVehicle(it) },
            fleet = buildFleet(vehicle, tripId),
            occupancy = buildOccupancy(vehicle),
            stops = stops,
            delay_seconds = delay ?: 0,
            has_delay = delay != null,
            geometry = if (includeGeometry) buildGeometry(feeds, tripId, stops) else null,
        )
    }

    /**
     * Map line for the leg, first poll only. Preferred: the weekly shapes
     * dataset keyed by trip_id (real track geometry). Fallback: straight
     * lines through the known stop coordinates — honest, never fabricated.
     * `track.geometry.straight_lines` is the shape-miss metric: sustained
     * non-zero means the dataset lags the timetable, regenerate it.
     */
    private suspend fun buildGeometry(
        feeds: List<FeedRegistry.FeedRef>,
        tripId: String,
        stops: List<StopProgress>,
    ): LegGeometry? {
        for (ref in feeds) {
            val polyline = datasets.polyline(ref.feed, tripId) ?: continue
            metrics.counter("track.geometry.shapes").inc()
            return LegGeometry(encoded_polyline = polyline, source = LegGeometry.Source.GTFS_SHAPES)
        }
        val points = stops
            .filter { it.latitude != 0.0 || it.longitude != 0.0 }
            .map { PolylineCodec.Point(it.latitude, it.longitude) }
        if (points.size >= 2) {
            metrics.counter("track.geometry.straight_lines").inc()
            return LegGeometry(
                encoded_polyline = PolylineCodec.encode(points),
                source = LegGeometry.Source.STOP_STRAIGHT_LINES,
            )
        }
        metrics.counter("track.geometry.none").inc()
        return null
    }

    /**
     * Platform-aware stop lookup: the tracking directory (T1.5 dataset,
     * carries train platform ids) wins; the bundled search dataset (parents
     * + bus stops) is the fallback.
     */
    private suspend fun resolveStop(stopId: String): StopDirectory.Stop? {
        if (stopId.isEmpty()) return null
        datasets.stop(stopId)?.let { return StopDirectory.Stop(it.name, it.lat, it.lon) }
        return stops.find(stopId)
    }

    private fun findVehicle(feed: FeedMessage, tripId: String): VehiclePosition? =
        feed.entity.asSequence()
            .mapNotNull { it.vehicle }
            .firstOrNull { it.trip?.trip_id?.trim() == tripId }

    private fun findTripUpdate(feed: FeedMessage, tripId: String): TripUpdate? =
        feed.entity.asSequence()
            .mapNotNull { it.trip_update }
            .firstOrNull { it.trip.trip_id?.trim() == tripId }

    private fun isPlannedDepartureInFuture(leg: TrackRequest.TrackLeg, now: Instant): Boolean =
        runCatching { Instant.parse(leg.planned_departure_utc) }.getOrNull()?.isAfter(now) == true

    private fun isPlannedDepartureOlderThan(leg: TrackRequest.TrackLeg, now: Instant, seconds: Long): Boolean =
        runCatching { Instant.parse(leg.planned_departure_utc) }.getOrNull()
            ?.isBefore(now.minusSeconds(seconds)) == true

    private fun mapVehicle(vp: VehiclePosition): VehicleLive {
        val pos = vp.position
        return VehicleLive(
            latitude = pos?.latitude?.toDouble() ?: 0.0,
            longitude = pos?.longitude?.toDouble() ?: 0.0,
            bearing_degrees = pos?.bearing ?: 0f,
            has_bearing = pos?.bearing != null,
            speed_mps = pos?.speed ?: 0f,
            has_speed = pos?.speed != null,
            measured_at_epoch_sec = vp.timestamp ?: 0L,
            stop_relation = when (vp.current_status) {
                VehiclePosition.VehicleStopStatus.INCOMING_AT -> VehicleLive.StopRelation.INCOMING_AT
                VehiclePosition.VehicleStopStatus.STOPPED_AT -> VehicleLive.StopRelation.STOPPED_AT
                VehiclePosition.VehicleStopStatus.IN_TRANSIT_TO -> VehicleLive.StopRelation.IN_TRANSIT_TO
                null -> VehicleLive.StopRelation.STOP_RELATION_UNSPECIFIED
            },
            at_or_next_stop_id = vp.stop_id ?: "",
        )
    }

    private fun buildFleet(vp: VehiclePosition?, tripId: String): FleetInfo? {
        // Live descriptor wins — it reflects substitutions.
        val liveModel = vp?.vehicle?.tfnsw_vehicle_descriptor?.vehicle_model?.trim()
        if (!liveModel.isNullOrBlank()) {
            // Trains report a bare set-type letter; other modes a full name.
            val asSetCode = TripIdParser.displayNameForSetCode(liveModel)
            return FleetInfo(
                display_name = asSetCode ?: liveModel,
                set_code = if (asSetCode != null) liveModel.uppercase() else "",
                car_count = vp.carriage_sequence.size,
                source = FleetInfo.Source.LIVE,
            )
        }
        val parsed = TripIdParser.parse(tripId) ?: return null
        return FleetInfo(
            display_name = parsed.displayName,
            set_code = parsed.setCode,
            car_count = parsed.carCount ?: 0,
            source = FleetInfo.Source.SCHEDULED,
        )
    }

    private fun buildOccupancy(vp: VehiclePosition?): OccupancyInfo? {
        if (vp == null) return null
        val overall = vp.occupancy_status?.let { mapOccupancy(it.value) }
            ?: OccupancyInfo.Level.LEVEL_UNSPECIFIED
        val cars = vp.carriage_sequence
            .sortedBy { it.position_in_consist ?: Int.MAX_VALUE }
            .mapIndexed { index, car ->
                OccupancyInfo.CarOccupancy(
                    // position_in_consist base differs by mode in live feeds
                    // (metro 0-based, sydneytrains 1-based) — re-sequence from
                    // sorted order so the contract is always 1..N.
                    sequence = index + 1,
                    label = car.name ?: "",
                    level = car.occupancy_status?.let { mapOccupancy(it.value) }
                        ?: OccupancyInfo.Level.LEVEL_UNSPECIFIED,
                    quiet_carriage = car.quiet_carriage ?: false,
                )
            }
            // A consist with zero known levels renders as an all-grey strip —
            // noise. Car count still reaches the UI via FleetInfo.car_count.
            .takeIf { list -> list.any { it.level != OccupancyInfo.Level.LEVEL_UNSPECIFIED } }
            ?: emptyList()
        if (cars.isEmpty() && overall == OccupancyInfo.Level.LEVEL_UNSPECIFIED) return null
        return OccupancyInfo(overall = overall, cars = cars)
    }

    /** GTFS OccupancyStatus (0-based) → contract Level (shifted +1, 0 = unknown). */
    private fun mapOccupancy(gtfsValue: Int): OccupancyInfo.Level =
        OccupancyInfo.Level.fromValue(gtfsValue + 1) ?: OccupancyInfo.Level.LEVEL_UNSPECIFIED

    private suspend fun buildStopTimeline(
        tripUpdate: TripUpdate?,
        vehicle: VehiclePosition?,
        now: Instant,
        tripKey: String,
        includeGeometry: Boolean,
    ): List<StopProgress> {
        val updates = tripUpdate?.stop_time_update ?: return emptyList()
        if (updates.isEmpty()) return emptyList()

        val currentSeq = vehicle?.current_stop_sequence
        val currentStopId = vehicle?.stop_id

        // NSW trims passed stops from the feed; re-attach the ones this
        // server has previously seen so every poll is a COMPLETE snapshot
        // of the run — even for clients that joined mid-trip.
        val trimmed = stopMemory.recordAndGetTrimmed(
            tripKey,
            updates.mapNotNull { stu ->
                stu.stop_id?.let {
                    TripStopMemory.RememberedStop(
                        stopId = it,
                        sequence = stu.stop_sequence,
                        lastEstimatedEpochSec = stu.arrival?.time ?: stu.departure?.time ?: 0L,
                    )
                }
            },
        )
        val passed = trimmed.map { remembered ->
            val known = resolveStop(remembered.stopId)
            StopProgress(
                stop_id = remembered.stopId,
                stop_name = known?.name ?: "",
                estimated_epoch_sec = remembered.lastEstimatedEpochSec,
                state = StopProgress.State.DEPARTED,
                // Coordinates only travel with geometry (contract: first poll).
                latitude = if (includeGeometry) known?.lat ?: 0.0 else 0.0,
                longitude = if (includeGeometry) known?.lon ?: 0.0 else 0.0,
            )
        }

        return passed + updates.map { stu ->
            val estimated = stu.arrival?.time ?: stu.departure?.time ?: 0L
            val skipped = stu.schedule_relationship ==
                TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED

            val state = when {
                skipped -> StopProgress.State.SKIPPED
                currentSeq != null && stu.stop_sequence != null -> when {
                    stu.stop_sequence!! < currentSeq -> StopProgress.State.DEPARTED
                    stu.stop_sequence == currentSeq -> StopProgress.State.CURRENT
                    else -> StopProgress.State.UPCOMING
                }
                currentStopId != null && stu.stop_id == currentStopId -> StopProgress.State.CURRENT
                estimated in 1 until now.epochSecond - 60 -> StopProgress.State.DEPARTED
                else -> StopProgress.State.UPCOMING
            }

            val stopId = stu.stop_id ?: ""
            val known = resolveStop(stopId)
            StopProgress(
                stop_id = stopId,
                stop_name = known?.name ?: "",
                estimated_epoch_sec = estimated,
                state = state,
                latitude = if (includeGeometry) known?.lat ?: 0.0 else 0.0,
                longitude = if (includeGeometry) known?.lon ?: 0.0 else 0.0,
            )
        }
    }

    /**
     * The feed carries the vehicle's FULL run; the user boards and alights
     * somewhere inside it. The full list is returned — clients may want
     * "what's coming after my stop" — with each stop tagged relative to
     * the user's journey (BEFORE/JOURNEY/AFTER) when both endpoint ids
     * match the sequence exactly (they do for trains/buses — Trip Planner
     * legs use the same platform-level ids as TripUpdates). On mismatch
     * every stop stays SEGMENT_UNSPECIFIED and clients render all stops
     * normally; a metric tracks how often that happens.
     */
    private fun List<StopProgress>.annotateSegments(originId: String, destinationId: String): List<StopProgress> {
        if (originId.isBlank() || destinationId.isBlank()) return this
        val from = indexOfFirst { it.stop_id == originId }
        val to = indexOfLast { it.stop_id == destinationId }
        if (from < 0 || to < from) {
            metrics.counter("track.segment.unmatched").inc()
            return this
        }
        metrics.counter("track.segment.matched").inc()
        return mapIndexed { i, stop ->
            stop.copy(
                segment = when {
                    i < from -> StopProgress.Segment.BEFORE_JOURNEY
                    i > to -> StopProgress.Segment.AFTER_JOURNEY
                    else -> StopProgress.Segment.JOURNEY
                },
            )
        }
    }

    private fun latestDelaySeconds(tripUpdate: TripUpdate?): Int? {
        val updates = tripUpdate?.stop_time_update ?: return null
        // The most recent passed/current stop's delay is the live truth;
        // fall back to the first available delay.
        return updates.lastOrNull { it.arrival?.delay != null || it.departure?.delay != null }
            ?.let { it.departure?.delay ?: it.arrival?.delay }
    }
}
