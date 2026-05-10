package app.krail.bff.mapper

import app.krail.bff.proto.ApiError
import app.krail.bff.proto.Coord
import app.krail.bff.proto.FacilityAvailability
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Maps a single NSW `/v1/carpark` (single-facility) JSON body → BFF proto
 * [FacilityAvailability]. Designed for the parking proto endpoint at
 * `/api/v1/parking/availability-proto`, which fans out N facility calls
 * and assembles them into a [ParkingAvailabilityResponse].
 *
 * NSW returns numbers as JSON strings (`"spots": "216"`) and uses
 * inconsistent null handling on `occupancy.total` / `occupancy.loop`.
 * This mapper:
 *  - Parses string-numbers via int parse, defaults to 0 on failure.
 *  - Treats `occupancy.total = null` as 0 occupied spots.
 *  - Pulls the `[lat, lon]` from `location.latitude` / `location.longitude`
 *    (note the spelling — NSW uses `latitude`/`longitude`, not `lat`/`lon`).
 *  - Falls back to "" / 0 for missing required string/int fields rather
 *    than dropping the facility — KRAIL renders a degraded card instead.
 */
object ParkingProtoMapper {

    private val logger = LoggerFactory.getLogger(ParkingProtoMapper::class.java)

    private val NSW_JSON = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    /**
     * Decode NSW's per-facility carpark JSON into a [FacilityAvailability].
     * The [requestedFacilityId] is the id used to make the upstream call
     * and is preferred over NSW's `facility_id` field on conflict (NSW is
     * usually consistent but we treat the caller's id as authoritative).
     */
    fun toProto(rawNswJson: String, requestedFacilityId: String): FacilityAvailability {
        val obj = runCatching { NSW_JSON.parseToJsonElement(rawNswJson).jsonObject }
            .getOrElse {
                logger.warn(
                    "carpark body for {} wasn't a JSON object; returning sentinel",
                    requestedFacilityId,
                )
                return FacilityAvailability(
                    facility_id = requestedFacilityId,
                    facility_name = "",
                    total_spots = 0,
                    occupied_spots = 0,
                )
            }

        val nswFacilityId = obj["facility_id"]?.jsonPrimitive?.contentOrNull
        val facilityId = nswFacilityId?.takeIf { it.isNotBlank() } ?: requestedFacilityId

        val facilityName = obj["facility_name"]?.jsonPrimitive?.contentOrNull.orEmpty()

        // NSW returns spots / occupancy values as JSON *strings* (or null).
        val totalSpots = obj["spots"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val occupancy = obj["occupancy"]?.jsonObject
        val occupied = occupancy?.get("total")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0

        val location = obj["location"]?.jsonObject
        val coord = location?.let { extractCoord(it) }
        val suburb = location?.get("suburb")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val address = location?.get("address")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        val updatedAt = obj["MessageDate"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        return FacilityAvailability(
            facility_id = facilityId,
            facility_name = facilityName,
            total_spots = totalSpots,
            occupied_spots = occupied,
            location = coord,
            suburb = suburb,
            address = address,
            updated_at = updatedAt,
        )
    }

    /**
     * Build an [ApiError] from a code + message. Trivial but co-located here
     * so route handlers don't import the proto type directly.
     */
    fun error(code: String, message: String): ApiError =
        ApiError(code = code, message = message)

    /**
     * NSW puts coordinates on `location.latitude` / `location.longitude`
     * (string-encoded). Validate before constructing.
     */
    private fun extractCoord(location: JsonObject): Coord? = runCatching {
        val lat = location["latitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        val lon = location["longitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        if (lat == null || lon == null) return@runCatching null
        if (lat.isNaN() || lon.isNaN()) return@runCatching null
        if (lat !in -90.0..90.0) return@runCatching null
        if (lon !in -180.0..180.0) return@runCatching null
        Coord(lat = lat, lon = lon)
    }.getOrNull()
}
