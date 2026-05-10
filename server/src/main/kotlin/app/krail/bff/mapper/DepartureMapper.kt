package app.krail.bff.mapper

import app.krail.bff.proto.Coord
import app.krail.bff.proto.DepartureBoardResponse
import app.krail.bff.proto.DepartureRow
import app.krail.bff.proto.StopRef
import app.krail.bff.proto.StopTime
import app.krail.bff.proto.TransitLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Maps NSW Transport `departure_mon` JSON → BFF proto `DepartureBoardResponse`.
 *
 * Implementation note: parses NSW JSON with `JsonElement` rather than a typed
 * Kotlin model. NSW's `departure_mon` schema has 60+ fields most of which we
 * don't surface; declaring all of them in a typed `data class` is busywork
 * that drops new fields silently when NSW adds them (same root cause as the
 * trip pass-through bug fixed earlier). JsonElement lets us pull only the
 * fields the screen needs and ignore the rest.
 *
 * Mapping summary (per stopEvent):
 *  - `transportation.disassembledName` → `TransitLine.display_name`
 *  - `transportation.product.class`    → `TransitLine.transport_mode_type`
 *  - `transportation.destination.name` → `DepartureRow.destination`
 *  - `departureTimePlanned`            → `StopTime.planned_utc`
 *  - `departureTimeEstimated`          → `StopTime.estimated_utc`
 *  - `isRealtimeControlled`            → `DepartureRow.is_realtime`
 *  - `location.disassembledName` (regex-extract platform/stand) → `platform_text`
 *  - `transportation.properties.RealtimeTripId` → `trip_id` (when set)
 *  - planned date vs today → `date_label` ("today"/"tomorrow"/null)
 */
object DepartureMapper {

    private val logger = LoggerFactory.getLogger(DepartureMapper::class.java)

    private val NSW_JSON = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    private val SYDNEY = ZoneId.of("Australia/Sydney")
    private val PLATFORM_REGEX = Regex(
        "(Platform|Stand|Wharf|Side)\\s*(\\d+|[A-Z])",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Build a [DepartureBoardResponse] from NSW's raw `departure_mon` JSON
     * body. The [requestedStopId] is what the client asked for and is echoed
     * back in [StopRef.id] so the response is self-identifying.
     *
     * Returns a response with empty `departures` if NSW didn't return any
     * (this is normal — late-night requests for low-frequency stops). Never
     * throws; malformed entries are logged and dropped.
     */
    fun toProto(rawNswJson: String, requestedStopId: String): DepartureBoardResponse {
        val root: JsonObject = runCatching { NSW_JSON.parseToJsonElement(rawNswJson).jsonObject }
            .getOrElse {
                logger.warn("departure_mon body wasn't a JSON object; returning empty board")
                return DepartureBoardResponse(
                    stop = StopRef(id = requestedStopId, name = ""),
                    departures = emptyList(),
                )
            }

        val stop = root["locations"]?.let { extractStopRef(it, fallback = requestedStopId) }
            ?: StopRef(id = requestedStopId, name = "")

        val rows = root["stopEvents"]?.jsonArray.orEmpty()
            .mapNotNull { e -> runCatching { mapStopEvent(e.jsonObject) }.getOrNull() }

        return DepartureBoardResponse(stop = stop, departures = rows)
    }

    private fun extractStopRef(locations: JsonElement, fallback: String): StopRef? {
        val first = locations.jsonArray.firstOrNull()?.jsonObject ?: return null
        val id = first["id"]?.jsonPrimitive?.contentOrNull ?: fallback
        val name = first["disassembledName"]?.jsonPrimitive?.contentOrNull
            ?: first["name"]?.jsonPrimitive?.contentOrNull
            ?: ""
        val coord = first["coord"]?.toCoord()
        return StopRef(id = id, name = name, coord = coord)
    }

    private fun mapStopEvent(ev: JsonObject): DepartureRow? {
        val transportation = ev["transportation"]?.jsonObject ?: return null
        val product = transportation["product"]?.jsonObject

        val displayName = transportation["disassembledName"]?.jsonPrimitive?.contentOrNull
            ?: transportation["name"]?.jsonPrimitive?.contentOrNull
            ?: return null

        // NSW's `product.class` is a number (1=Train etc). Allow long or int.
        val modeType = product?.get("class")?.jsonPrimitive?.let {
            it.intOrNull ?: it.longOrNull?.toInt()
        } ?: 0

        val destination = transportation["destination"]?.jsonObject?.get("name")
            ?.jsonPrimitive?.contentOrNull ?: ""

        val planned = ev["departureTimePlanned"]?.jsonPrimitive?.contentOrNull ?: return null
        val estimated = ev["departureTimeEstimated"]?.jsonPrimitive?.contentOrNull

        val isRealtime = ev["isRealtimeControlled"]?.jsonPrimitive?.booleanOrNull ?: false

        val location = ev["location"]?.jsonObject
        val platform = location?.get("disassembledName")?.jsonPrimitive?.contentOrNull
            ?.let { extractPlatform(it) }

        val tripId = transportation["properties"]?.jsonObject
            ?.get("RealtimeTripId")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }

        val dateLabel = computeDateLabel(planned)

        return DepartureRow(
            line = TransitLine(display_name = displayName, transport_mode_type = modeType),
            destination = destination,
            time = StopTime(planned_utc = planned, estimated_utc = estimated),
            platform_text = platform,
            date_label = dateLabel,
            trip_id = tripId,
            is_realtime = isRealtime,
        )
    }

    /**
     * Pulls the platform/stand label out of NSW's `location.disassembledName`
     * (often "Town Hall Station, Platform 3"). Same logic as
     * [JourneyListMapper]'s platform extraction. Returns the first match
     * verbatim ("Platform 3") or null if no platform/stand pattern present.
     */
    private fun extractPlatform(disassembledName: String): String? {
        val match = PLATFORM_REGEX.find(disassembledName) ?: return null
        return match.value
    }

    /**
     * Compares NSW's planned UTC timestamp to today/tomorrow in Sydney time.
     * Returns "today" / "tomorrow" / null. Anything beyond tomorrow returns
     * null (caller can show the absolute date). Anything malformed returns null.
     */
    private fun computeDateLabel(plannedIsoUtc: String): String? = runCatching {
        val planned = Instant.parse(plannedIsoUtc).atZone(SYDNEY).toLocalDate()
        val today = LocalDate.now(SYDNEY)
        when (planned) {
            today -> "today"
            today.plusDays(1) -> "tomorrow"
            else -> null
        }
    }.getOrNull()

    /**
     * Best-effort `[lat, lon]` → [Coord] conversion from NSW's stop coord
     * field. NSW returns this as either a 2-element JSON array or a nested
     * object; we accept both. Out-of-range / NaN inputs return null.
     */
    private fun JsonElement.toCoord(): Coord? = runCatching {
        val (lat, lon) = when {
            this is kotlinx.serialization.json.JsonArray && size == 2 -> {
                this[0].jsonPrimitive.doubleOrNull to this[1].jsonPrimitive.doubleOrNull
            }
            this is JsonObject -> {
                this["lat"]?.jsonPrimitive?.doubleOrNull to
                    this["lon"]?.jsonPrimitive?.doubleOrNull
            }
            else -> null to null
        }
        if (lat == null || lon == null) return@runCatching null
        if (lat.isNaN() || lon.isNaN()) return@runCatching null
        if (lat !in -90.0..90.0) return@runCatching null
        if (lon !in -180.0..180.0) return@runCatching null
        Coord(lat = lat, lon = lon)
    }.getOrNull()
}
