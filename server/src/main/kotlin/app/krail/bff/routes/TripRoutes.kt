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

                val response = nswClient.getTrip(
                    originStopId = origin,
                    destinationStopId = destination,
                    depArr = depArr,
                    date = date,
                    time = time,
                    excludedModes = excludedModes
                )

                call.respond(HttpStatusCode.OK, response)
            }
        }
    }
}

