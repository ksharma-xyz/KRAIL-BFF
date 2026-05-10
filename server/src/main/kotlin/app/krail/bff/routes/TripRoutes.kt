package app.krail.bff.routes

import app.krail.bff.client.nsw.NswClient
import app.krail.bff.model.TripRequestError
import app.krail.bff.model.parseTripRequest
import app.krail.bff.model.validate
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/**
 * Configure trip planning routes.
 *
 * Endpoints:
 * - /v1/tp/trip - Legacy Android endpoint (protobuf response)
 * - /api/v1/trip/plan - New JSON endpoint
 * - /api/v1/trip/plan-proto - New protobuf endpoint
 *
 * All endpoints support both legacy and new parameter formats for backward compatibility.
 */
fun Application.configureTripRoutes() {
    val nswClient by inject<NswClient>()

    routing {
        // Legacy Android endpoint - Returns JSON (original NSW API format)
        route("/v1/tp") {
            /**
             * GET /v1/tp/trip
             *
             * Legacy Android app endpoint. Returns JSON in NSW Transport API format.
             * This is a pass-through endpoint that returns the exact response from NSW API.
             * Supports both old parameter names (name_origin, name_destination, etc.)
             * and new parameter names (origin, destination, etc.) for compatibility.
             *
             * SUCCESS Response (200):
             *   Content-Type: application/json
             *   Body: NSW Transport API trip response (journeys, version, etc.)
             *
             * ERROR Responses (4xx/5xx):
             *   Content-Type: application/json
             *   Body: { "error": "...", "message": "...", "statusCode": ... }
             *
             * Query parameters (supports both formats):
             * - origin / name_origin: Origin stop ID (required)
             * - destination / name_destination: Destination stop ID (required)
             * - depArr / depArrMacro: "dep" or "arr" (optional, default: "dep")
             * - date / itdDate: Date in YYYYMMDD format (optional)
             * - time / itdTime: Time in HHmm format (optional)
             * - excludedModes / excludedMeans: Comma-separated mode IDs or "checkbox" (optional)
             */
            get("/trip") {
                call.handleTripJsonRequest(nswClient)
            }
        }

        route("/api/v1/trip") {
            /**
             * GET /api/v1/trip/plan
             *
             * Returns trip planning data as JSON.
             * Supports both new and legacy parameter formats for backward compatibility.
             *
             * Query parameters (supports both formats):
             * - origin / name_origin: Origin stop ID (required)
             * - destination / name_destination: Destination stop ID (required)
             * - depArr / depArrMacro: "dep" or "arr" (optional, default: "dep")
             * - date / itdDate: Date in YYYYMMDD format (optional)
             * - time / itdTime: Time in HHmm format (optional)
             * - excludedModes / excludedMeans: Comma-separated mode IDs (optional)
             */
            get("/plan") {
                call.handleTripJsonRequest(nswClient)
            }

            /**
             * GET /api/v1/trip/plan-proto
             *
             * Returns trip planning data as Protocol Buffers binary.
             * Supports both new and legacy parameter formats for backward compatibility.
             *
             * SUCCESS Response (200):
             *   Content-Type: application/protobuf
             *   Body: JourneyList protobuf bytes (83% smaller than JSON)
             *
             * ERROR Responses (4xx/5xx):
             *   Content-Type: application/json
             *   Body: { "error": "...", "message": "...", "statusCode": ... }
             *
             * Query parameters (supports both formats):
             * - origin / name_origin: Origin stop ID (required)
             * - destination / name_destination: Destination stop ID (required)
             * - depArr / depArrMacro: "dep" or "arr" (optional, default: "dep")
             * - date / itdDate: Date in YYYYMMDD format (optional)
             * - time / itdTime: Time in HHmm format (optional)
             * - excludedModes / excludedMeans: Comma-separated mode IDs (optional)
             */
            get("/plan-proto") {
                call.handleTripProtoRequest(nswClient)
            }
        }
    }
}

/**
 * Handle trip request and return JSON response.
 *
 * **True pass-through.** Returns NSW's JSON body byte-for-byte, including
 * fields the BFF's typed `TripResponse` model doesn't declare (`coords`,
 * `coupledTripsInfo`, `fare`, `interchanges`, `parent`, stop-level `coord`,
 * etc.) — ~70% of NSW's response payload was being silently stripped when
 * we previously routed through `nswClient.getTrip(...)` and re-serialized
 * the typed object.
 *
 * Why pass-through, not "fix the model": a complete TripResponse model
 * would need to track 200+ NSW fields; any future NSW addition would
 * silently drop again. Pass-through is robust by construction — anything
 * NSW returns flows through.
 *
 * The proto endpoint ([handleTripProtoRequest]) keeps the typed parse
 * because its mapper requires structure.
 */
private suspend fun ApplicationCall.handleTripJsonRequest(nswClient: NswClient) {
    val tripRequest = parseTripRequest()
        ?: return respond(HttpStatusCode.BadRequest, TripRequestError.MissingOrigin.toErrorResponse())

    tripRequest.validate()?.let { err ->
        return respond(HttpStatusCode.fromValue(err.statusCode), err.toErrorResponse())
    }

    val rawJson = nswClient.getTripRaw(
        originStopId = tripRequest.origin,
        destinationStopId = tripRequest.destination,
        depArr = tripRequest.depArr,
        date = tripRequest.date,
        time = tripRequest.time,
        excludedModes = tripRequest.excludedModes,
    )
    respondText(text = rawJson, contentType = ContentType.Application.Json, status = HttpStatusCode.OK)
}

/**
 * Handle trip request and return Protocol Buffers response.
 * Shared logic for protobuf endpoints (/v1/tp/trip and /api/v1/trip/plan-proto).
 */
suspend fun ApplicationCall.handleTripProtoRequest(nswClient: NswClient) {
    val tripRequest = parseTripRequest()
        ?: return respond(HttpStatusCode.BadRequest, TripRequestError.MissingOrigin.toErrorResponse())

    tripRequest.validate()?.let { err ->
        return respond(HttpStatusCode.fromValue(err.statusCode), err.toErrorResponse())
    }

    val journeyList = nswClient.getTripProto(
        originStopId = tripRequest.origin,
        destinationStopId = tripRequest.destination,
        depArr = tripRequest.depArr,
        date = tripRequest.date,
        time = tripRequest.time,
        excludedModes = tripRequest.excludedModes,
    )
    respondBytes(
        bytes = journeyList.encode(),
        contentType = io.ktor.http.ContentType.Application.ProtoBuf,
    )
}
