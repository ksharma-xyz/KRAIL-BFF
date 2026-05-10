package app.krail.bff.routes

import app.krail.bff.client.nsw.NswBudgetExceededException
import app.krail.bff.client.nsw.NswClient
import app.krail.bff.client.nsw.NswUpstreamException
import app.krail.bff.data.ParkRideStopFacilityMap
import app.krail.bff.mapper.ParkingProtoMapper
import app.krail.bff.proto.ApiError
import app.krail.bff.proto.FacilityAvailability
import app.krail.bff.proto.ParkingAvailabilityResponse
import app.krail.bff.proto.StopParkingBlock
import app.krail.bff.util.correlationIdOrNull
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("app.krail.bff.routes.ParkingRoutes")

// Compile-once: validates path-segment facility IDs.
private val FACILITY_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,40}$")

// Validates each entry in ?stopIds=. NSW transit stop IDs are numeric and
// up to ~10 chars, optionally namespaced (e.g. "NSW:2155384"). Same shape
// as DepartureRoutes.DEP_STOP_ID_REGEX.
private val STOP_ID_REGEX = Regex("^[A-Za-z0-9:]{1,40}$")

// KRAIL Home renders ≤ 5 saved-trip cards in practice; 20 leaves comfortable
// headroom and still bounds upstream fan-out.
private const val BATCH_MAX_STOP_IDS = 20

private const val INVALID_FACILITY_ID_BODY =
    "{\"error\":{\"code\":\"invalid_facility_id\"," +
    "\"message\":\"facilityId must be alphanumeric, 1-40 chars\"}}"

private const val BATCH_MISSING_STOP_IDS_BODY =
    "{\"error\":{\"code\":\"missing_stop_ids\"," +
    "\"message\":\"Provide ?stopIds=<comma-separated transit stop IDs>\"}}"

private const val BATCH_TOO_MANY_STOP_IDS_BODY =
    "{\"error\":{\"code\":\"too_many_stop_ids\"," +
    "\"message\":\"At most $BATCH_MAX_STOP_IDS stop IDs per batch\"}}"

// Reused for parsing each NSW carpark response so we can embed it as a nested
// JSON object in the JSON batch body. Hoisting per the Json{} reuse pattern
// in NswClient.
private val PARKING_JSON = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
}

/**
 * Park & Ride endpoints.
 *
 * The KRAIL home screen renders one parking card per saved trip; each
 * stop has 1–3 facilities (e.g. Tallawong → P1 / P2 / P3). The BFF
 * fans out concurrently so KRAIL fires one HTTP request regardless
 * of facility count, taking roughly the time of the slowest NSW call.
 *
 * Endpoints:
 *
 *  - `GET /v1/parking/facilities`
 *      List of facility IDs → names. NSW pass-through.
 *
 *  - `GET /v1/parking/facilities/{facilityId}/availability`
 *      Single facility availability. NSW pass-through.
 *
 *  - `GET /v1/parking/availability?stopIds=275010,2155384`
 *      Batch of stop-id resolution → fan-out. Returns JSON keyed by
 *      stopId, each block carrying `facilities` + `errors` maps,
 *      plus a top-level `unknownStops` list for stops with no
 *      mapping. Per-IP rate limit counts the batch as 1 request.
 *      **Stop-id mode only** — `?ids=` was deprecated; KRAIL doesn't
 *      use it.
 *
 *  - `GET /api/v1/parking/availability-proto?stopIds=...`
 *      Same fan-out, screen-shaped binary protobuf
 *      (`ParkingAvailabilityResponse`). Smaller wire than JSON for
 *      multi-stop batches.
 */
fun Application.configureParkingRoutes() {
    val nsw by inject<NswClient>()

    routing {
        // ---- Single-facility / list pass-through ----
        route("/v1/parking/facilities") {
            get {
                val body = nsw.getCarparkRaw(facilityId = null)
                call.respondText(text = body, contentType = ContentType.Application.Json)
            }

            get("/{facilityId}/availability") {
                val facilityId = call.parameters["facilityId"]
                if (facilityId.isNullOrBlank() || !facilityId.matches(FACILITY_ID_REGEX)) {
                    call.respondText(
                        text = INVALID_FACILITY_ID_BODY,
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.BadRequest,
                    )
                    return@get
                }
                val body = nsw.getCarparkRaw(facilityId = facilityId)
                call.respondText(text = body, contentType = ContentType.Application.Json)
            }
        }

        // ---- JSON batch (?stopIds= only) ----
        get("/v1/parking/availability") {
            call.handleStopIdBatchJson(nsw)
        }

        // ---- Screen-shaped protobuf (?stopIds= only) ----
        get("/api/v1/parking/availability-proto") {
            call.handleStopIdBatchProto(nsw)
        }
    }
}

// ---------------------------------------------------------------------------
// Stop-id resolution + concurrent fan-out — shared between JSON and proto.
// ---------------------------------------------------------------------------

/**
 * Result of one fan-out NSW call within the batch. The JSON / proto
 * builders both pivot on this discriminator.
 */
private sealed class FacilityResult {
    abstract val id: String

    data class Ok(override val id: String, val rawBody: String) : FacilityResult()
    data class Err(override val id: String, val code: String, val message: String) : FacilityResult()
}

/**
 * Parsed-and-validated input for one stopIds batch request. Returns
 * `null` and writes a 400 to the call when the input is missing or
 * over the cap; the route handler returns immediately in that case.
 */
private data class StopBatchInput(
    val resolvedStops: LinkedHashMap<String, List<String>>,
    val unknownStops: List<String>,
    val uniqueFacilityIds: List<String>,
)

private suspend fun ApplicationCall.parseStopIdsOrFail(): StopBatchInput? {
    val raw = request.queryParameters["stopIds"]
    if (raw.isNullOrBlank()) {
        respondText(BATCH_MISSING_STOP_IDS_BODY, ContentType.Application.Json, HttpStatusCode.BadRequest)
        return null
    }

    val rawStopIds = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (rawStopIds.size > BATCH_MAX_STOP_IDS) {
        respondText(BATCH_TOO_MANY_STOP_IDS_BODY, ContentType.Application.Json, HttpStatusCode.BadRequest)
        return null
    }
    if (rawStopIds.isEmpty()) {
        respondText(BATCH_MISSING_STOP_IDS_BODY, ContentType.Application.Json, HttpStatusCode.BadRequest)
        return null
    }

    val resolvedStops = LinkedHashMap<String, List<String>>()
    val unknownStops = mutableListOf<String>()
    for (stopId in rawStopIds) {
        if (!stopId.matches(STOP_ID_REGEX)) {
            unknownStops += stopId
            continue
        }
        // Strip "NSW:" namespace prefix before lookup; preserve the raw key
        // in the response.
        val canonical = stopId.substringAfter(':', stopId)
        val facilities = ParkRideStopFacilityMap.facilitiesFor(canonical)
        if (facilities == null) {
            unknownStops += stopId
        } else {
            resolvedStops[stopId] = facilities
        }
    }

    val uniqueFacilityIds = resolvedStops.values.flatten().distinct()
    return StopBatchInput(resolvedStops, unknownStops, uniqueFacilityIds)
}

/**
 * Concurrent NSW fan-out per unique facility. Two stops aliasing the
 * same facility (e.g. Hornsby 25 has two stop IDs) → one NSW call.
 */
private suspend fun fetchAllFacilities(
    nsw: NswClient,
    uniqueFacilityIds: List<String>,
): Map<String, FacilityResult> = coroutineScope {
    uniqueFacilityIds
        .associateWith { id -> async { fetchOne(nsw, id) } }
        .mapValues { (_, deferred) -> deferred.await() }
}

private suspend fun fetchOne(nsw: NswClient, id: String): FacilityResult =
    try {
        val raw = nsw.getCarparkRaw(facilityId = id)
        FacilityResult.Ok(id, raw)
    } catch (e: NswBudgetExceededException) {
        FacilityResult.Err(id, "daily_budget_exceeded", e.message ?: "NSW daily call budget exhausted")
    } catch (e: NswUpstreamException) {
        FacilityResult.Err(
            id = id,
            code = if (e.statusCode in 500..599) "upstream_error" else "upstream_${e.statusCode}",
            message = e.message ?: "NSW returned ${e.statusCode}",
        )
    } catch (e: Throwable) {
        logger.warn("Unexpected error fetching facility {}: {}", id, e.message)
        FacilityResult.Err(id, "upstream_error", e.message ?: e::class.simpleName ?: "unknown")
    }

// ---------------------------------------------------------------------------
// JSON builder — current /v1/parking/availability response shape.
// ---------------------------------------------------------------------------

private suspend fun ApplicationCall.handleStopIdBatchJson(nsw: NswClient) {
    val input = parseStopIdsOrFail() ?: return
    val resultsByFacility = fetchAllFacilities(nsw, input.uniqueFacilityIds)

    val body = buildJsonObject {
        putJsonObject("stops") {
            for ((stopId, fids) in input.resolvedStops) {
                putJsonObject(stopId) {
                    putJsonObject("facilities") {
                        for (fid in fids) {
                            val r = resultsByFacility[fid]
                            if (r is FacilityResult.Ok) {
                                // Embed NSW's body as a nested JSON object.
                                val parsed = runCatching {
                                    PARKING_JSON.parseToJsonElement(r.rawBody)
                                }.getOrNull()
                                if (parsed != null) put(fid, parsed)
                            }
                        }
                    }
                    putJsonObject("errors") {
                        for (fid in fids) {
                            val r = resultsByFacility[fid]
                            if (r is FacilityResult.Err) {
                                putJsonObject(fid) {
                                    put("code", r.code)
                                    put("message", r.message)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (input.unknownStops.isNotEmpty()) {
            put("unknownStops", JsonArray(input.unknownStops.map { JsonPrimitive(it) }))
        }
        correlationIdOrNull()?.let { put("correlationId", it) }
    }

    respondText(
        text = PARKING_JSON.encodeToString(JsonObject.serializer(), body),
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.OK,
    )
}

// ---------------------------------------------------------------------------
// Proto builder — /api/v1/parking/availability-proto response shape.
// ---------------------------------------------------------------------------

private suspend fun ApplicationCall.handleStopIdBatchProto(nsw: NswClient) {
    val input = parseStopIdsOrFail() ?: return
    val resultsByFacility = fetchAllFacilities(nsw, input.uniqueFacilityIds)

    // Build per-stop blocks. For each stop we walk its facility-id list
    // and bucket each id into either `facilities` (Ok → mapped proto)
    // or `errors` (Err → ApiError).
    val stopBlocks: Map<String, StopParkingBlock> = input.resolvedStops
        .mapValues { (_, fids) ->
            val facilities = mutableMapOf<String, FacilityAvailability>()
            val errors = mutableMapOf<String, ApiError>()
            for (fid in fids) {
                when (val r = resultsByFacility[fid]) {
                    is FacilityResult.Ok ->
                        facilities[fid] = ParkingProtoMapper.toProto(r.rawBody, requestedFacilityId = fid)
                    is FacilityResult.Err ->
                        errors[fid] = ParkingProtoMapper.error(r.code, r.message)
                    null -> Unit  // unreachable; fan-out covers every unique id.
                }
            }
            StopParkingBlock(facilities = facilities, errors = errors)
        }

    val response = ParkingAvailabilityResponse(
        // Top-level facilities/errors stay empty in stopIds-only mode —
        // the schema reserves them for a future ?ids= mode if it returns.
        facilities = emptyMap(),
        errors = emptyMap(),
        stops = stopBlocks,
        unknown_stops = input.unknownStops,
        correlation_id = correlationIdOrNull().orEmpty(),
    )

    respondBytes(
        bytes = response.encode(),
        contentType = ContentType.Application.ProtoBuf,
    )
}
