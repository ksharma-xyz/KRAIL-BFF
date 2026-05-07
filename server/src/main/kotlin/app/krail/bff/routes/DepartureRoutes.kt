package app.krail.bff.routes

import app.krail.bff.client.nsw.NswClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/**
 * Departure board endpoints. Pass-through of NSW `departure_mon` for v1; the
 * client will see the same JSON shape NSW returns. Screen-shaping is a future
 * refactor (per API_SCHEMA_DESIGN.md §2.2).
 *
 * Path-based: GET /v1/stops/{stopId}/departures?date=&time=
 */
fun Application.configureDepartureRoutes() {
    val nsw by inject<NswClient>()

    routing {
        route("/v1/stops/{stopId}/departures") {
            get {
                val stopId = call.parameters["stopId"]
                if (stopId.isNullOrBlank() || !stopId.matches(Regex("^[A-Za-z0-9:]{1,40}$"))) {
                    call.respondText(
                        text = "{\"error\":{\"code\":\"invalid_stop_id\",\"message\":\"stopId must be alphanumeric (with optional namespace prefix), 1-40 chars\"}}",
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.BadRequest,
                    )
                    return@get
                }
                val date = call.request.queryParameters["date"]?.takeIf { it.matches(Regex("^\\d{8}$")) }
                val time = call.request.queryParameters["time"]?.takeIf { it.matches(Regex("^\\d{4}$")) }

                // Strip city namespace prefix (e.g. "NSW:200060" -> "200060") before calling NSW
                val nswStopId = stopId.substringAfter(':', stopId)
                val body = nsw.getDeparturesRaw(stopId = nswStopId, date = date, time = time)
                call.respondText(text = body, contentType = ContentType.Application.Json)
            }
        }
    }
}
