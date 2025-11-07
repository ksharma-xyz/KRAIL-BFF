package app.krail.bff.routes

import app.krail.bff.client.nsw.NswClient
import app.krail.bff.model.TripRequestError
import app.krail.bff.model.parseTripRequest
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
        // Legacy Android endpoint - Returns protobuf
        route("/v1/tp") {
            /**
             * GET /v1/tp/trip
             *
             * Legacy Android app endpoint. Returns Protocol Buffers binary data.
             * Supports both old parameter names (name_origin, name_destination, etc.)
             * and new parameter names (origin, destination, etc.) for compatibility.
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
             * - excludedModes / excludedMeans: Comma-separated mode IDs or "checkbox" (optional)
             */
            get("/trip") {
                call.handleTripProtoRequest(nswClient)
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
 * Shared logic for JSON endpoints.
 */
private suspend fun ApplicationCall.handleTripJsonRequest(nswClient: NswClient) {
    val tripRequest = parseTripRequest()
        ?: return respond(HttpStatusCode.BadRequest, TripRequestError.MissingOrigin.toErrorResponse())

    try {
        val response = nswClient.getTrip(
            originStopId = tripRequest.origin,
            destinationStopId = tripRequest.destination,
            depArr = tripRequest.depArr,
            date = tripRequest.date,
            time = tripRequest.time,
            excludedModes = tripRequest.excludedModes
        )

        respond(HttpStatusCode.OK, response)
    } catch (e: IllegalStateException) {
        // NSW API returned an error status (4xx or 5xx)
        respond(
            HttpStatusCode.BadGateway,
            mapOf(
                "error" to "Bad Gateway",
                "message" to "NSW Transport API error: ${e.message ?: "Unknown error"}",
                "statusCode" to 502
            )
        )
    } catch (e: Exception) {
        // Other errors (network, timeout, parsing, etc.)
        respond(
            HttpStatusCode.InternalServerError,
            mapOf(
                "error" to "Internal Server Error",
                "message" to "Failed to fetch trip data: ${e.message ?: "Unknown error"}",
                "statusCode" to 500
            )
        )
    }
}

/**
 * Handle trip request and return Protocol Buffers response.
 * Shared logic for protobuf endpoints (/v1/tp/trip and /api/v1/trip/plan-proto).
 */
suspend fun ApplicationCall.handleTripProtoRequest(nswClient: NswClient) {
    val tripRequest = parseTripRequest()
        ?: return respond(
            HttpStatusCode.BadRequest,
            mapOf(
                "error" to "Bad Request",
                "message" to "Missing 'origin' or 'destination' parameter",
                "statusCode" to 400
            )
        )

    try {
        val journeyList = nswClient.getTripProto(
            originStopId = tripRequest.origin,
            destinationStopId = tripRequest.destination,
            depArr = tripRequest.depArr,
            date = tripRequest.date,
            time = tripRequest.time,
            excludedModes = tripRequest.excludedModes
        )

        // Return protobuf binary data
        respondBytes(
            bytes = journeyList.encode(),
            contentType = io.ktor.http.ContentType.Application.ProtoBuf
        )
    } catch (e: IllegalStateException) {
        // NSW API returned an error status (4xx or 5xx)
        respond(
            HttpStatusCode.BadGateway,
            mapOf(
                "error" to "Bad Gateway",
                "message" to "NSW Transport API error: ${e.message ?: "Unknown error"}",
                "statusCode" to 502
            )
        )
    } catch (e: Exception) {
        // Other errors (network, timeout, parsing, etc.)
        respond(
            HttpStatusCode.InternalServerError,
            mapOf(
                "error" to "Internal Server Error",
                "message" to "Failed to fetch trip data: ${e.message ?: "Unknown error"}",
                "statusCode" to 500
            )
        )
    }
}
