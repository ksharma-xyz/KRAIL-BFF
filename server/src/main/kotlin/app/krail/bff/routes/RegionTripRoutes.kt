package app.krail.bff.routes

import app.krail.bff.region.RegionRegistry
import app.krail.bff.region.RegionSpec
import app.krail.bff.region.RegionTripService
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

// Compile-once validation patterns (allowlist, length-bounded), shared by
// every region. Public stop ids look like "VIC:vic:rail:FSS",
// "QLD:place_censta", "AKL:otah", "BER:de:11000:900100003::2" — letters,
// digits, ':', '_', '-' cover all seven regions' id schemes.
private val STOP_ID_REGEX = Regex("^[A-Za-z0-9:_-]{1,48}$")
private val DATE_REGEX = Regex("^\\d{8}$")
private val TIME_REGEX = Regex("^([01]\\d|2[0-3])[0-5]\\d$")

private const val INVALID_STOP_ID_BODY =
    "{\"error\":{\"code\":\"invalid_stop_id\"," +
        "\"message\":\"origin and destination must be stop ids (letters, digits, ':', '_', '-'), 1-48 chars\"}}"
private const val INVALID_DATE_BODY =
    "{\"error\":{\"code\":\"invalid_date\",\"message\":\"date must be YYYYMMDD\"}}"
private const val INVALID_TIME_BODY =
    "{\"error\":{\"code\":\"invalid_time\",\"message\":\"time must be HHmm\"}}"

/**
 * Journey planning for every BFF-routed region ([RegionRegistry]) — served
 * from the in-process RAPTOR router, not an upstream API (none of these
 * cities has one), the pattern VIC proved and QLD confirmed.
 *
 * Feature-gated per region: `/api/v1/{code}/trip/plan-proto` registers only
 * when `{CODE}_ROUTER_SNAPSHOT` (env, or `{code}.routerSnapshot` config)
 * points at a readable router snapshot. All unset in production until the
 * dataset pipeline ships — the NSW paths are untouched, and one region's
 * load failure never affects another's.
 *
 * - GET /api/v1/{code}/trip/plan-proto?origin=&destination=&date=&time=
 *   origin/destination: "{PREFIX}:"-prefixed stop or station id (prefix optional)
 *   date: YYYYMMDD, time: HHmm — default to now in the region's timezone
 *   Returns JourneyList protobuf bytes; errors are JSON envelopes.
 */
fun Application.configureRegionTripRoutes() {
    val services = LinkedHashMap<String, RegionTripService>()
    // Registered even when empty: Router Lab reads it to list loaded regions.
    attributes.put(RegionTripServicesKey, services)
    for (spec in RegionRegistry.all) {
        val snapshotPath = System.getenv(spec.snapshotEnvVar)
            ?: environment.config.propertyOrNull(spec.snapshotConfigKey)?.getString()
        if (snapshotPath.isNullOrBlank()) {
            log.info("{} trip routes disabled: no {} configured", spec.code, spec.snapshotEnvVar)
            continue
        }
        val service = try {
            val model = RouterSnapshot.read(Path.of(snapshotPath))
            log.info(
                "{} router model loaded: {} stops, {} trips, calendar {}..{}",
                spec.code, model.stopCount, model.tripCount,
                model.meta.calendarStart, model.meta.calendarEnd,
            )
            RegionTripService(model, spec)
        } catch (e: Exception) {
            log.error("${spec.code} router snapshot load failed; ${spec.code} trip routes disabled", e)
            continue
        }
        services[spec.code] = service
        regionTripRoutes(spec, service)
    }
}

/**
 * Region code → loaded trip service; contains only the regions whose
 * snapshot loaded at startup. Router Lab reads this for its dropdown and
 * per-region stop search.
 */
val RegionTripServicesKey =
    AttributeKey<MutableMap<String, RegionTripService>>("RegionTripServices")

internal fun Application.regionTripRoutes(spec: RegionSpec, service: RegionTripService) {
    routing {
        get("/api/v1/${spec.code}/trip/plan-proto") {
            val origin = call.request.queryParameters["origin"]
            val destination = call.request.queryParameters["destination"]
            if (origin.isNullOrBlank() || !origin.matches(STOP_ID_REGEX) ||
                destination.isNullOrBlank() || !destination.matches(STOP_ID_REGEX)
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
                date = if (dateParam.matches(DATE_REGEX)) {
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
            if (time != null && !time.matches(TIME_REGEX)) {
                return@get call.respondText(
                    text = INVALID_TIME_BODY,
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.BadRequest,
                )
            }

            val originId = origin.removePrefix("${spec.idPrefix}:")
            val destinationId = destination.removePrefix("${spec.idPrefix}:")
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
