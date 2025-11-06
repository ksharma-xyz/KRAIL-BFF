package app.krail.bff.routes

import app.krail.bff.client.nsw.NswClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Application.configureTripRoutes() {
    val nswClient by inject<NswClient>()

    routing {
        route("/api/v1/trip") {
            /**
             * GET /api/v1/trip/plan
             *
             * Query parameters:
             * - origin: Origin stop ID (required)
             * - destination: Destination stop ID (required)
             * - depArr: "dep" or "arr" (optional, default: "dep")
             * - date: Date in YYYYMMDD format (optional)
             * - time: Time in HHmm format (optional)
             * - excludedModes: Comma-separated transport mode IDs to exclude (optional)
             */
            get("/plan") {
                val origin = call.request.queryParameters["origin"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing 'origin' parameter"))

                val destination = call.request.queryParameters["destination"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing 'destination' parameter"))

                val depArr = call.request.queryParameters["depArr"] ?: "dep"
                val date = call.request.queryParameters["date"]
                val time = call.request.queryParameters["time"]

                val excludedModes = call.request.queryParameters["excludedModes"]
                    ?.split(",")
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?.toSet()
                    ?: emptySet()

                try {
                    val response = nswClient.getTrip(
                        originStopId = origin,
                        destinationStopId = destination,
                        depArr = depArr,
                        date = date,
                        time = time,
                        excludedModes = excludedModes
                    )

                    call.respond(HttpStatusCode.OK, response)
                } catch (e: IllegalStateException) {
                    // NSW API returned an error status (4xx or 5xx)
                    call.respond(
                        HttpStatusCode.BadGateway,
                        mapOf(
                            "error" to "NSW Transport API error",
                            "message" to (e.message ?: "Unknown error from upstream API")
                        )
                    )
                } catch (e: Exception) {
                    // Other errors (network, timeout, etc.)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf(
                            "error" to "Failed to fetch trip data",
                            "message" to (e.message ?: "Unknown error")
                        )
                    )
                }
            }

            /**
             * GET /api/v1/trip/plan-proto
             *
             * Returns protobuf binary data on success, JSON on error
             *
             * SUCCESS Response (200):
             *   Content-Type: application/protobuf
             *   Body: JourneyList protobuf bytes
             *
             * ERROR Responses (4xx/5xx):
             *   Content-Type: application/json
             *   Body: { "error": "...", "message": "...", "statusCode": ... }
             *
             * Query parameters:
             * - origin: Origin stop ID (required)
             * - destination: Destination stop ID (required)
             * - depArr: "dep" or "arr" (optional, default: "dep")
             * - date: Date in YYYYMMDD format (optional)
             * - time: Time in HHmm format (optional)
             * - excludedModes: Comma-separated transport mode IDs to exclude (optional)
             */
            get("/plan-proto") {
                val origin = call.request.queryParameters["origin"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "error" to "Bad Request",
                            "message" to "Missing 'origin' parameter",
                            "statusCode" to 400
                        )
                    )

                val destination = call.request.queryParameters["destination"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "error" to "Bad Request",
                            "message" to "Missing 'destination' parameter",
                            "statusCode" to 400
                        )
                    )

                val depArr = call.request.queryParameters["depArr"] ?: "dep"
                val date = call.request.queryParameters["date"]
                val time = call.request.queryParameters["time"]

                val excludedModes = call.request.queryParameters["excludedModes"]
                    ?.split(",")
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?.toSet()
                    ?: emptySet()

                try {
                    val journeyList = nswClient.getTripProto(
                        originStopId = origin,
                        destinationStopId = destination,
                        depArr = depArr,
                        date = date,
                        time = time,
                        excludedModes = excludedModes
                    )

                    // Return protobuf binary data
                    call.respondBytes(
                        bytes = journeyList.encode(),
                        contentType = io.ktor.http.ContentType.Application.ProtoBuf
                    )
                } catch (e: IllegalStateException) {
                    // NSW API returned an error status (4xx or 5xx)
                    call.respond(
                        HttpStatusCode.BadGateway,
                        mapOf(
                            "error" to "Bad Gateway",
                            "message" to "NSW Transport API error: ${e.message ?: "Unknown error"}",
                            "statusCode" to 502
                        )
                    )
                } catch (e: Exception) {
                    // Other errors (network, timeout, parsing, etc.)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf(
                            "error" to "Internal Server Error",
                            "message" to "Failed to fetch trip data: ${e.message ?: "Unknown error"}",
                            "statusCode" to 500
                        )
                    )
                }
            }
        }
    }
}
