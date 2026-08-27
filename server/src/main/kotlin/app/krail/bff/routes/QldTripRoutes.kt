package app.krail.bff.routes

import app.krail.bff.qld.QldTripService
import app.krail.bff.router.RouterSnapshot
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// Compile-once validation patterns (allowlist, length-bounded). QLD public
// stop ids look like "QLD:600016" (platform) or "QLD:place_censta" (station).
private val QLD_STOP_ID_REGEX = Regex("^[A-Za-z0-9:_-]{1,48}$")
private val QLD_DATE_REGEX = Regex("^\\d{8}$")
private val QLD_TIME_REGEX = Regex("^([01]\\d|2[0-3])[0-5]\\d$")

private const val INVALID_STOP_ID_BODY =
    "{\"error\":{\"code\":\"invalid_stop_id\"," +
        "\"message\":\"origin and destination must be stop ids (letters, digits, ':', '_', '-'), 1-48 chars\"}}"
private const val INVALID_DATE_BODY =
    "{\"error\":{\"code\":\"invalid_date\",\"message\":\"date must be YYYYMMDD\"}}"
private const val INVALID_TIME_BODY =
    "{\"error\":{\"code\":\"invalid_time\",\"message\":\"time must be HHmm\"}}"

/**
 * QLD (SEQ / Brisbane) journey planning — served from the in-process RAPTOR
 * router, not an upstream API (Translink has none), mirroring the VIC path.
 *
 * Feature-gated: routes register only when QLD_ROUTER_SNAPSHOT (env, or
 * `qld.routerSnapshot` config) points at a readable router snapshot. Unset in
 * production until the dataset pipeline ships — the NSW paths are untouched.
 *
 * - GET /api/v1/qld/trip/plan-proto?origin=&destination=&date=&time=
 *   origin/destination: "QLD:"-prefixed stop or station id (prefix optional)
 *   date: YYYYMMDD, time: HHmm — default to now in Brisbane
 *   Returns JourneyList protobuf bytes; errors are JSON envelopes.
 */
fun Application.configureQldTripRoutes() {
    val snapshotPath = System.getenv("QLD_ROUTER_SNAPSHOT")
        ?: environment.config.propertyOrNull("qld.routerSnapshot")?.getString()
    if (snapshotPath.isNullOrBlank()) {
        log.info("qld trip routes disabled: no QLD_ROUTER_SNAPSHOT configured")
        return
    }
    val service = try {
        val model = RouterSnapshot.read(Path.of(snapshotPath))
        log.info(
            "qld router model loaded: {} stops, {} trips, calendar {}..{}",
            model.stopCount, model.tripCount, model.meta.calendarStart, model.meta.calendarEnd,
        )
        QldTripService(model)
    } catch (e: Exception) {
        log.error("qld router snapshot load failed; qld trip routes disabled", e)
        return
    }
    // Later-registered dev routes (Router Lab stop search) find the loaded
    // service here; absent key = QLD routing not configured.
    attributes.put(QldTripServiceKey, service)
    qldTripRoutes(service)
}

/** Set only when the QLD router model loaded at startup. */
val QldTripServiceKey = AttributeKey<QldTripService>("QldTripService")

internal fun Application.qldTripRoutes(service: QldTripService) {
    routing {
        get("/api/v1/qld/trip/plan-proto") {
            val origin = call.request.queryParameters["origin"]
            val destination = call.request.queryParameters["destination"]
            if (origin.isNullOrBlank() || !origin.matches(QLD_STOP_ID_REGEX) ||
                destination.isNullOrBlank() || !destination.matches(QLD_STOP_ID_REGEX)
            ) {
                return@get call.respondText(
                    text = INVALID_STOP_ID_BODY,
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.BadRequest,
                )
            }
            // Parse, not just pattern-match: 20260230 passes \d{8} but is not
            // a calendar date and must be a 400, never an exception downstream.
            val dateParam = call.request.queryParameters["date"]
            var date: LocalDate? = null
            if (dateParam != null) {
                date = if (dateParam.matches(QLD_DATE_REGEX)) {
                    runCatching { LocalDate.parse(dateParam, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
                } else {
                    null
                }
                if (date == null) {
                    return@get call.respondText(
                        text = INVALID_DATE_BODY,
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.BadRequest,
                    )
                }
            }
            val time = call.request.queryParameters["time"]
            if (time != null && !time.matches(QLD_TIME_REGEX)) {
                return@get call.respondText(
                    text = INVALID_TIME_BODY,
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.BadRequest,
                )
            }

            val originId = origin.removePrefix("QLD:")
            val destinationId = destination.removePrefix("QLD:")
            if (!service.knowsStop(originId) || !service.knowsStop(destinationId)) {
                // StatusPages owns 404 bodies app-wide (ErrorEnvelope + correlationId).
                return@get call.respond(HttpStatusCode.NotFound)
            }

            val journeyList = service.planProto(originId, destinationId, date, time)
            // Accept: application/json → JSON mirror (Router Lab); default protobuf.
            call.respondJourneyList(journeyList)
        }
    }
}
