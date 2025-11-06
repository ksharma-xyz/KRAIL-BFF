package app.krail.bff.mapper

import app.krail.bff.model.TripResponse
import app.krail.bff.proto.JourneyList
import app.krail.bff.proto.JourneyCardInfo
import app.krail.bff.proto.TransportModeLine
import app.krail.bff.proto.Leg
import app.krail.bff.proto.WalkingLeg
import app.krail.bff.proto.TransportLeg
import app.krail.bff.proto.Stop
import app.krail.bff.proto.WalkInterchange
import app.krail.bff.proto.WalkPosition
import app.krail.bff.proto.ServiceAlert
import app.krail.bff.proto.DepartureDeviation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.Duration
import kotlin.math.absoluteValue

/**
 * Mapper to convert JSON TripResponse to Protocol Buffer JourneyList.
 * This implements the same mapping logic as the Kotlin Multiplatform client.
 */
object JourneyListMapper {

    private val timeFormatter = DateTimeFormatter.ofPattern("h:mma")
        .withZone(ZoneId.of("Australia/Sydney"))

    /**
     * Main conversion function: TripResponse -> JourneyList
     */
    fun toProto(tripResponse: TripResponse): JourneyList {
        val journeys = tripResponse.journeys
            ?.mapNotNull { journey -> mapJourney(journey) }
            ?: emptyList()

        return JourneyList(journeys = journeys)
    }

    private fun mapJourney(journey: TripResponse.Journey): JourneyCardInfo? {
        val legs = journey.legs ?: return null

        // Filter out legs without transportation info (except walking legs)
        val validLegs = legs.filter { leg ->
            leg.transportation != null || leg.isWalkingLeg()
        }

        if (validLegs.isEmpty()) return null

        // Find first and last public transport legs
        val firstPublicTransportLeg = legs.firstOrNull { !it.isWalkingLeg() }
        val lastPublicTransportLeg = legs.lastOrNull { !it.isWalkingLeg() }

        val originTimeUTC = firstPublicTransportLeg?.getDepartureTime() ?: return null
        val arrivalTimeUTC = lastPublicTransportLeg?.getArrivalTime() ?: return null

        val totalStops = validLegs.sumOf { it.stopSequence?.size ?: 0 }
        if (totalStops == 0) return null

        // Map transport mode lines
        val transportModeLines = validLegs.mapNotNull { leg ->
            leg.transportation?.let { transport ->
                val productClass = transport.product?.productClass?.toInt() ?: return@mapNotNull null
                val lineName = transport.disassembledName ?: transport.name ?: return@mapNotNull null

                TransportModeLine(
                    line_name = lineName,
                    transport_mode_type = productClass
                )
            }
        }

        // Map legs to proto format
        val protoLegs = validLegs.mapNotNull { leg -> mapLeg(leg) }

        // Calculate total walking duration
        val totalWalkingDuration = validLegs.sumOf { leg ->
            if (leg.isWalkingLeg() && leg.footPathInfoRedundant != true) {
                leg.duration ?: 0L
            } else {
                0L
            }
        }

        val walkingDurationStr = if (totalWalkingDuration > 0L) {
            formatDuration(totalWalkingDuration)
        } else {
            null
        }

        // Count unique service alerts
        val totalUniqueServiceAlerts = validLegs
            .flatMap { it.infos ?: emptyList() }
            .distinctBy { it.id }
            .size

        return JourneyCardInfo(
            time_text = calculateTimeUntil(originTimeUTC),
            platform_text = firstPublicTransportLeg.getPlatformText(),
            platform_number = firstPublicTransportLeg.getPlatformNumber(),
            origin_time = formatTime(originTimeUTC),
            origin_utc_date_time = originTimeUTC,
            destination_time = formatTime(arrivalTimeUTC),
            destination_utc_date_time = arrivalTimeUTC,
            travel_time = formatDuration(
                Duration.between(
                    Instant.parse(originTimeUTC),
                    Instant.parse(arrivalTimeUTC)
                ).seconds
            ),
            total_walk_time = walkingDurationStr,
            transport_mode_lines = transportModeLines,
            legs = protoLegs,
            total_unique_service_alerts = totalUniqueServiceAlerts,
            departure_deviation = firstPublicTransportLeg.getDepartureDeviation()
        )
    }

    private fun mapLeg(leg: TripResponse.Leg): Leg? {
        return if (leg.isWalkingLeg()) {
            // Walking leg
            if (leg.footPathInfoRedundant == true) {
                null // Skip redundant walking legs
            } else {
                val duration = leg.duration ?: return null
                Leg(
                    walking_leg = WalkingLeg(
                        duration = formatDuration(duration)
                    )
                )
            }
        } else {
            // Transport leg
            val transport = leg.transportation ?: return null
            val productClass = transport.product?.productClass?.toInt() ?: return null
            val lineName = transport.disassembledName ?: transport.name ?: return null
            val duration = leg.duration ?: return null
            val stops = leg.stopSequence?.mapNotNull { mapStop(it) } ?: return null

            val displayText = when (productClass) {
                1, 2 -> "towards ${transport.destination?.name}" // Train, Metro
                else -> transport.description
            }

            val walkInterchange = leg.footPathInfo?.firstOrNull()?.let { footPath ->
                footPath.duration?.let { dur ->
                    WalkInterchange(
                        duration = formatDuration(dur),
                        position = mapWalkPosition(footPath.position)
                    )
                }
            }

            val serviceAlerts = leg.infos?.mapNotNull { info ->
                ServiceAlert(
                    id = info.id ?: "",
                    subtitle = info.subtitle ?: "",
                    content = info.content ?: "",
                    priority = info.priority ?: "normal",
                    url = info.url
                )
            } ?: emptyList()

            val tripId = transport.id.orEmpty() +
                         (transport.properties?.realtimeTripId ?: "")

            Leg(
                transport_leg = TransportLeg(
                    transport_mode_line = TransportModeLine(
                        line_name = lineName,
                        transport_mode_type = productClass
                    ),
                    display_text = displayText,
                    total_duration = formatDuration(duration),
                    stops = stops,
                    walk_interchange = walkInterchange,
                    service_alert_list = serviceAlerts,
                    trip_id = tripId.ifEmpty { null }
                )
            )
        }
    }

    private fun mapStop(stopSeq: TripResponse.StopSequence): Stop? {
        val name = stopSeq.disassembledName ?: stopSeq.name ?: return null
        val time = stopSeq.departureTimeEstimated
            ?: stopSeq.departureTimePlanned
            ?: stopSeq.arrivalTimeEstimated
            ?: stopSeq.arrivalTimePlanned
            ?: return null

        return Stop(
            name = name,
            time = formatTime(time),
            is_wheelchair_accessible = stopSeq.properties?.wheelchairAccess?.lowercase() == "true"
        )
    }

    private fun mapWalkPosition(position: String?): WalkPosition {
        return when (position?.uppercase()) {
            "BEFORE" -> WalkPosition.BEFORE
            "AFTER" -> WalkPosition.AFTER
            "IDEST" -> WalkPosition.IDEST
            else -> WalkPosition.WALK_POSITION_UNSPECIFIED
        }
    }

    // Helper functions

    private fun TripResponse.Leg.isWalkingLeg(): Boolean {
        val productClass = transportation?.product?.productClass
        return productClass == 99L || productClass == 100L
    }

    private fun TripResponse.Leg.getDepartureTime(): String? {
        return origin?.departureTimeEstimated ?: origin?.departureTimePlanned
    }

    private fun TripResponse.Leg.getArrivalTime(): String? {
        return destination?.arrivalTimeEstimated ?: destination?.arrivalTimePlanned
    }

    private fun TripResponse.Leg?.getPlatformText(): String? {
        val disassembledName = this?.origin?.disassembledName ?: return null
        val regex = Regex("(Platform|Stand|Wharf|Side)\\s*(\\d+|[A-Z])", RegexOption.IGNORE_CASE)
        val matches = regex.findAll(disassembledName).toList()
        return if (matches.isNotEmpty()) matches.joinToString(", ") { it.value } else null
    }

    private fun TripResponse.Leg?.getPlatformNumber(): String? {
        val disassembledName = this?.origin?.disassembledName ?: return null
        val regex = Regex("(Platform|Stand|Wharf)\\s*(\\d+|[A-Z])", RegexOption.IGNORE_CASE)
        val match = regex.find(disassembledName)
        return match?.groupValues?.getOrNull(2)
    }

    private fun TripResponse.Leg?.getDepartureDeviation(): DepartureDeviation? {
        val estimated = this?.origin?.departureTimeEstimated
        val planned = this?.origin?.departureTimePlanned

        if (estimated == null || planned == null) return null

        return try {
            val estInstant = Instant.parse(estimated)
            val plannedInstant = Instant.parse(planned)
            val diff = Duration.between(plannedInstant, estInstant)
            val mins = diff.toMinutes()

            when {
                mins == 0L -> DepartureDeviation(on_time = true)
                mins > 0L -> {
                    val abs = mins.absoluteValue
                    val unit = if (abs == 1L) "min" else "mins"
                    DepartureDeviation(late = "$abs $unit late")
                }
                else -> {
                    val abs = mins.absoluteValue
                    val unit = if (abs == 1L) "min" else "mins"
                    DepartureDeviation(early = "$abs $unit early")
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateTimeUntil(originTime: String): String {
        return try {
            val originInstant = Instant.parse(originTime)
            val now = Instant.now()
            val duration = Duration.between(now, originInstant)
            val minutesUntil = duration.toMinutes()

            when {
                minutesUntil <= 0 -> "now"
                minutesUntil == 1L -> "in 1 min"
                minutesUntil < 60 -> "in $minutesUntil mins"
                else -> {
                    val hours = minutesUntil / 60
                    val mins = minutesUntil % 60
                    if (mins == 0L) "in $hours hr" else "in $hours hr $mins min"
                }
            }
        } catch (e: Exception) {
            "now"
        }
    }

    private fun formatTime(isoTime: String): String {
        return try {
            val instant = Instant.parse(isoTime)
            timeFormatter.format(instant).lowercase()
        } catch (e: Exception) {
            isoTime
        }
    }

    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        return when {
            minutes == 0L -> "< 1 min"
            minutes == 1L -> "1 min"
            minutes < 60 -> "$minutes mins"
            else -> {
                val hours = minutes / 60
                val mins = minutes % 60
                if (mins == 0L) "$hours hr" else "$hours hr $mins min"
            }
        }
    }
}

