package app.krail.bff.routes

import app.krail.bff.client.nsw.NswClient
import app.krail.bff.mapper.DepartureMapper
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

// Compile-once regex patterns for input validation. Allocating Regex per request
// is wasteful — Pattern compilation is non-trivial and patterns are immutable
// + thread-safe, so file-level vals are the right choice.
private val DEP_STOP_ID_REGEX = Regex("^[A-Za-z0-9:]{1,40}$")
private val DEP_DATE_REGEX = Regex("^\\d{8}$")
private val DEP_TIME_REGEX = Regex("^\\d{4}$")

// Pre-built error body — same string every invalid stopId, no need to rebuild.
private const val INVALID_STOP_ID_BODY =
    "{\"error\":{\"code\":\"invalid_stop_id\"," +
    "\"message\":\"stopId must be alphanumeric (with optional namespace prefix), 1-40 chars\"}}"

/**
 * Departure board endpoints.
 *
 * - GET /v1/stops/{stopId}/departures?date=&time=
 *   Pass-through of NSW `departure_mon`. JSON, NSW shape verbatim.
 *
 * - GET /api/v1/stops/{stopId}/departures-proto?date=&time=
 *   Screen-shaped binary protobuf (`DepartureBoardResponse`). Built by
 *   parsing NSW's JSON and mapping the screen-relevant fields
 *   (line + destination + planned/realtime times + platform + trip_id).
 *
 * Both share the same input validation + NSW upstream call; only the
 * response encoding differs.
 */
fun Application.configureDepartureRoutes() {
    val nsw by inject<NswClient>()

    routing {
        // ---- Pass-through JSON ----
        route("/v1/stops/{stopId}/departures") {
            get {
                val stopId = call.parameters["stopId"]
                if (stopId.isNullOrBlank() || !stopId.matches(DEP_STOP_ID_REGEX)) {
                    call.respondText(
                        text = INVALID_STOP_ID_BODY,
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.BadRequest,
                    )
                    return@get
                }
                val date = call.request.queryParameters["date"]?.takeIf { it.matches(DEP_DATE_REGEX) }
                val time = call.request.queryParameters["time"]?.takeIf { it.matches(DEP_TIME_REGEX) }

                // Strip city namespace prefix (e.g. "NSW:200060" -> "200060") before calling NSW
                val nswStopId = stopId.substringAfter(':', stopId)
                val body = nsw.getDeparturesRaw(stopId = nswStopId, date = date, time = time)
                call.respondText(text = body, contentType = ContentType.Application.Json)
            }
        }

        // ---- Screen-shaped protobuf ----
        get("/api/v1/stops/{stopId}/departures-proto") {
            val stopId = call.parameters["stopId"]
            if (stopId.isNullOrBlank() || !stopId.matches(DEP_STOP_ID_REGEX)) {
                call.respondText(
                    text = INVALID_STOP_ID_BODY,
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.BadRequest,
                )
                return@get
            }
            val date = call.request.queryParameters["date"]?.takeIf { it.matches(DEP_DATE_REGEX) }
            val time = call.request.queryParameters["time"]?.takeIf { it.matches(DEP_TIME_REGEX) }

            val nswStopId = stopId.substringAfter(':', stopId)
            val rawJson = nsw.getDeparturesRaw(stopId = nswStopId, date = date, time = time)
            val proto = DepartureMapper.toProto(rawJson, requestedStopId = stopId)
            call.respondBytes(
                bytes = proto.encode(),
                contentType = ContentType.Application.ProtoBuf,
            )
        }
    }
}
