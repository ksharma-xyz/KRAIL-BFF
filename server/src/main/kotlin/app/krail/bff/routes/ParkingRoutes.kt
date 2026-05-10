package app.krail.bff.routes

import app.krail.bff.client.nsw.NswClient
import app.krail.bff.client.nsw.NswBudgetExceededException
import app.krail.bff.client.nsw.NswUpstreamException
import app.krail.bff.data.ParkRideStopFacilityMap
import app.krail.bff.util.correlationIdOrNull
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("app.krail.bff.routes.ParkingRoutes")

// Compile-once: validates path-segment facility IDs (and each entry in the
// batch ?ids= param).
private val FACILITY_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,40}$")

// Validates each entry in ?stopIds=. NSW transit stop IDs are numeric and
// up to ~10 chars, optionally namespaced (e.g. "NSW:2155384"). Same shape
// as DepartureRoutes.STOP_ID_REGEX.
private val STOP_ID_REGEX = Regex("^[A-Za-z0-9:]{1,40}$")

// Batch endpoint caps. KRAIL Home renders ≤ 5 saved-trip cards in practice;
// 20 leaves comfortable headroom and still bounds upstream fan-out.
private const val BATCH_MAX_IDS = 20
private const val BATCH_MAX_STOP_IDS = 20

private const val INVALID_FACILITY_ID_BODY =
    "{\"error\":{\"code\":\"invalid_facility_id\"," +
    "\"message\":\"facilityId must be alphanumeric, 1-40 chars\"}}"

private const val BATCH_MISSING_IDS_BODY =
    "{\"error\":{\"code\":\"missing_ids\"," +
    "\"message\":\"Provide ?ids=<comma-separated facility IDs>\"}}"

private const val BATCH_TOO_MANY_IDS_BODY =
    "{\"error\":{\"code\":\"too_many_ids\"," +
    "\"message\":\"At most $BATCH_MAX_IDS IDs per batch\"}}"

private const val BATCH_MISSING_STOP_IDS_BODY =
    "{\"error\":{\"code\":\"missing_stop_ids\"," +
    "\"message\":\"Provide ?stopIds=<comma-separated transit stop IDs>\"}}"

private const val BATCH_TOO_MANY_STOP_IDS_BODY =
    "{\"error\":{\"code\":\"too_many_stop_ids\"," +
    "\"message\":\"At most $BATCH_MAX_STOP_IDS stop IDs per batch\"}}"

// Reused for parsing each NSW carpark response so we can embed it as a nested
// JSON object in the batch body. Hoisting per the Json{} reuse pattern in
// NswClient.
private val PARKING_JSON = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
}

/**
 * Park & Ride endpoints. Pass-through of NSW `/v1/carpark` for v1, plus a
 * batch endpoint that fans out concurrently for the home-screen "saved
 * trips with parking facilities" use case.
 *
 * - GET /v1/parking/facilities
 *     List of all facilities. NSW returns the full map when called without
 *     a facility= param. Returns Map<facilityId, facilityName> JSON.
 *
 * - GET /v1/parking/facilities/{facilityId}/availability
 *     Single facility's current occupancy. Pass-through of NSW's
 *     /v1/carpark?facility={facilityId}.
 *
 * - GET /v1/parking/availability?ids=486,487,488
 *     **Batch** of single-facility availability calls, fanned out in
 *     parallel server-side. The KRAIL home screen renders one parking
 *     card per saved trip, often with 2–3 facilities each (P1, P2, P3).
 *     Without batching, the app fires N sequential or near-parallel HTTP
 *     calls per card mount, each with a TLS handshake and round-trip cost
 *     on cellular. With this endpoint, KRAIL fires one BFF call regardless
 *     of facility count; the BFF runs N NSW calls concurrently, taking
 *     roughly the time of the slowest NSW call.
 *
 *     Per-IP rate-limit win: a batch of N counts as 1 call (vs N before).
 *     NSW daily-budget impact: still N (each upstream call consumes 1).
 */
fun Application.configureParkingRoutes() {
    val nsw by inject<NswClient>()

    routing {
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

        get("/v1/parking/availability") {
            // Two query-param modes, selected by which is present:
            //   ?ids=486,487     — facility-ID batch (existing)
            //   ?stopIds=275010  — stop-ID lookup (resolves to facility IDs server-side)
            // If both are present, stopIds wins — it's strictly more abstract.
            val stopIdsRaw = call.request.queryParameters["stopIds"]
            if (!stopIdsRaw.isNullOrBlank()) {
                call.handleStopIdBatch(nsw)
            } else {
                call.handleParkingBatch(nsw)
            }
        }
    }
}

/**
 * Result of one fan-out call within the batch. The wrapping endpoint maps
 * these into either the `facilities` or `errors` slot in the response.
 */
private sealed class FacilityResult {
    abstract val id: String

    data class Ok(override val id: String, val body: JsonElement) : FacilityResult()
    data class Err(override val id: String, val code: String, val message: String) : FacilityResult()
}

private suspend fun ApplicationCall.handleParkingBatch(nsw: NswClient) {
    val raw = request.queryParameters["ids"]
    if (raw.isNullOrBlank()) {
        respondText(BATCH_MISSING_IDS_BODY, ContentType.Application.Json, HttpStatusCode.BadRequest)
        return
    }

    // Split, trim, dedupe (preserving insertion order), drop empties.
    val ids = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (ids.size > BATCH_MAX_IDS) {
        respondText(BATCH_TOO_MANY_IDS_BODY, ContentType.Application.Json, HttpStatusCode.BadRequest)
        return
    }
    if (ids.isEmpty()) {
        respondText(BATCH_MISSING_IDS_BODY, ContentType.Application.Json, HttpStatusCode.BadRequest)
        return
    }

    // Per-id validation: invalid IDs become errors in the response rather
    // than failing the whole batch. The client gets back a fixed-shape
    // response keyed by every id it asked for.
    val (validIds, invalidIds) = ids.partition { it.matches(FACILITY_ID_REGEX) }

    // Fan out concurrently. Each NSW call is independent; coroutineScope
    // ensures we don't return until all complete (or any single one cancels
    // the rest on a structured-concurrency exception, e.g. budget exceeded).
    val results: List<FacilityResult> = coroutineScope {
        validIds.map { id ->
            async {
                fetchOne(nsw, id)
            }
        }.awaitAll()
    } + invalidIds.map { id ->
        FacilityResult.Err(
            id = id,
            code = "invalid_facility_id",
            message = "facilityId must be alphanumeric, 1-40 chars",
        )
    }

    val body = buildBatchResponse(results, correlationId = correlationIdOrNull())

    // 200 even on partial failure — the client gets a structured
    // facilities/errors split. Whole-batch failure (e.g. all NSW calls
    // upstream-erred) still returns 200 with empty facilities + populated
    // errors; the client treats no successes as a degraded response.
    respondText(body, ContentType.Application.Json, HttpStatusCode.OK)
}

private suspend fun fetchOne(nsw: NswClient, id: String): FacilityResult =
    try {
        val raw = nsw.getCarparkRaw(facilityId = id)
        // Parse to JsonElement so we can embed the body as a nested object
        // in the batch response. If NSW returns malformed JSON, treat as an
        // upstream_error rather than crashing the batch.
        val parsed = runCatching { PARKING_JSON.parseToJsonElement(raw) }.getOrNull()
        if (parsed == null) {
            logger.warn("NSW carpark returned non-JSON for facility {}; treating as upstream_error", id)
            FacilityResult.Err(id, "upstream_error", "NSW returned non-JSON body")
        } else {
            FacilityResult.Ok(id, parsed)
        }
    } catch (e: NswBudgetExceededException) {
        // Budget exhaustion is a global condition — record it on every
        // remaining facility so the client knows the whole batch was
        // affected by quota, not just one facility.
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

private fun buildBatchResponse(results: List<FacilityResult>, correlationId: String?): String {
    val obj = buildJsonObject {
        putJsonObject("facilities") {
            for (r in results) if (r is FacilityResult.Ok) put(r.id, r.body)
        }
        putJsonObject("errors") {
            for (r in results) if (r is FacilityResult.Err) {
                putJsonObject(r.id) {
                    put("code", r.code)
                    put("message", r.message)
                }
            }
        }
        if (correlationId != null) put("correlationId", correlationId)
    }
    return PARKING_JSON.encodeToString(JsonObject.serializer(), obj)
}

// ============================================================================
// ?stopIds= variant — server-side resolves transit stop IDs to facility IDs.
// See ParkRideStopFacilityMap for the lookup data + rationale for keeping it
// server-side. Response shape:
//
//   {
//     "stops": {
//       "275010": {                            // resolved stop
//         "facilities": { "21": {...}, "22": {...} },
//         "errors": {}                         // per-facility upstream errors
//       },
//       "2155384": {
//         "facilities": { "26": {...}, "28": {...} },
//         "errors": { "27": {"code":"upstream_404","message":"..."} }
//       }
//     },
//     "unknownStops": ["999999"],              // stops with no mapping
//     "correlationId": "..."
//   }
//
// The client renders one card group per resolved stop, hiding (or empty-state-
// rendering) stops in the unknownStops array. Each facility within a stop
// is either in `facilities` (success) or `errors` (per-facility failure).
// ============================================================================

/** One stop resolved against the lookup, with per-facility outcomes attached. */
private data class StopResult(
    val stopId: String,
    val facilities: List<FacilityResult>,
)

private suspend fun ApplicationCall.handleStopIdBatch(nsw: NswClient) {
    val raw = request.queryParameters["stopIds"]
    if (raw.isNullOrBlank()) {
        respondText(BATCH_MISSING_STOP_IDS_BODY, ContentType.Application.Json, HttpStatusCode.BadRequest)
        return
    }

    // Same parsing rules as ?ids=: split, trim, dedupe, drop empties.
    val rawStopIds = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (rawStopIds.size > BATCH_MAX_STOP_IDS) {
        respondText(BATCH_TOO_MANY_STOP_IDS_BODY, ContentType.Application.Json, HttpStatusCode.BadRequest)
        return
    }
    if (rawStopIds.isEmpty()) {
        respondText(BATCH_MISSING_STOP_IDS_BODY, ContentType.Application.Json, HttpStatusCode.BadRequest)
        return
    }

    // Resolve each stop ID via the static lookup. Strip any "NSW:" namespace
    // prefix the client may have sent (consistent with DepartureRoutes).
    // Three buckets:
    //   - resolvedStops: { stopId -> [facilityIds] }
    //   - invalidFormat: stops with shapes that fail STOP_ID_REGEX
    //   - unknownStops:  stops that pass regex but aren't in our map
    val resolvedStops = LinkedHashMap<String, List<String>>()
    val invalidFormat = mutableListOf<String>()
    val unknownStops = mutableListOf<String>()

    for (stopId in rawStopIds) {
        if (!stopId.matches(STOP_ID_REGEX)) {
            invalidFormat += stopId
            continue
        }
        val canonical = stopId.substringAfter(':', stopId)
        val facilities = ParkRideStopFacilityMap.facilitiesFor(canonical)
        if (facilities == null) {
            unknownStops += stopId
        } else {
            resolvedStops[stopId] = facilities
        }
    }

    // Dedupe the union of all facility IDs across resolved stops — multiple
    // stops can alias the same facility (e.g. Hornsby 25 has two stop IDs).
    // We fan out exactly once per unique facility, then de-multiplex back
    // to per-stop responses.
    val uniqueFacilityIds: List<String> = resolvedStops.values.flatten().distinct()

    // Concurrent NSW fan-out per unique facility.
    val resultsByFacility: Map<String, FacilityResult> = coroutineScope {
        uniqueFacilityIds.associateWith { id ->
            async { fetchOne(nsw, id) }
        }
    }.mapValues { (_, deferred) -> deferred.await() }

    // Build per-stop StopResult by joining resolved facility IDs back to results.
    val stopResults: List<StopResult> = resolvedStops.map { (stopId, fids) ->
        StopResult(
            stopId = stopId,
            facilities = fids.mapNotNull { resultsByFacility[it] },
        )
    }

    val body = buildStopBatchResponse(
        stops = stopResults,
        unknownStops = unknownStops + invalidFormat,
        correlationId = correlationIdOrNull(),
    )

    respondText(body, ContentType.Application.Json, HttpStatusCode.OK)
}

private fun buildStopBatchResponse(
    stops: List<StopResult>,
    unknownStops: List<String>,
    correlationId: String?,
): String {
    val obj = buildJsonObject {
        putJsonObject("stops") {
            for (s in stops) {
                putJsonObject(s.stopId) {
                    putJsonObject("facilities") {
                        for (f in s.facilities) if (f is FacilityResult.Ok) put(f.id, f.body)
                    }
                    putJsonObject("errors") {
                        for (f in s.facilities) if (f is FacilityResult.Err) {
                            putJsonObject(f.id) {
                                put("code", f.code)
                                put("message", f.message)
                            }
                        }
                    }
                }
            }
        }
        if (unknownStops.isNotEmpty()) {
            put(
                "unknownStops",
                kotlinx.serialization.json.JsonArray(
                    unknownStops.map { kotlinx.serialization.json.JsonPrimitive(it) }
                ),
            )
        }
        if (correlationId != null) put("correlationId", correlationId)
    }
    return PARKING_JSON.encodeToString(JsonObject.serializer(), obj)
}
