package app.krail.bff.routes

import app.krail.bff.client.nsw.NswClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/**
 * GTFS-Realtime pass-through endpoints. Pure proxy: NSW returns protobuf bytes,
 * we return the same bytes. The KRAIL app's existing GtfsRealtimeMatcher
 * decodes and matches client-side.
 *
 * Server-side matching (so we ship only the matched vehicle per request) is a
 * future refactor — see API_SCHEMA_DESIGN.md §2.5b for the target shape.
 *
 * Allowed feed-name characters: alphanumeric, `_`, `-`, `/` (for nested feeds
 * like `lightrail/cbdandsoutheast`). Anything else → 400.
 *
 * - GET /v1/gtfs/realtime/{feed} — trip updates / alerts (NSW v1 feeds)
 * - GET /v2/gtfs/realtime/{feed} — trip updates / alerts (NSW v2 feeds: sydneytrains, metro)
 * - GET /v2/gtfs/vehiclepos/{feed} — vehicle positions
 */
fun Application.configureGtfsRoutes() {
    val nsw by inject<NswClient>()

    routing {
        get("/v1/gtfs/realtime/{feed...}") { call.proxyRealtime(nsw, version = 1) }
        get("/v2/gtfs/realtime/{feed...}") { call.proxyRealtime(nsw, version = 2) }
        get("/v2/gtfs/vehiclepos/{feed...}") { call.proxyVehiclePos(nsw) }
    }
}

private val FEED_REGEX = Regex("^[A-Za-z0-9_/-]{1,64}$")

private suspend fun io.ktor.server.application.ApplicationCall.proxyRealtime(nsw: NswClient, version: Int) {
    val feed = parameters.getAll("feed")?.joinToString("/")
    if (feed.isNullOrBlank() || !feed.matches(FEED_REGEX)) {
        respondText(
            text = "{\"error\":{\"code\":\"invalid_feed\",\"message\":\"feed must be alphanumeric (slashes / underscores / hyphens allowed), 1-64 chars\"}}",
            contentType = ContentType.Application.Json,
            status = HttpStatusCode.BadRequest,
        )
        return
    }
    val bytes = nsw.getGtfsRealtimeRaw(version = version, feed = feed)
    respondBytes(bytes = bytes, contentType = ContentType.Application.ProtoBuf)
}

private suspend fun io.ktor.server.application.ApplicationCall.proxyVehiclePos(nsw: NswClient) {
    val feed = parameters.getAll("feed")?.joinToString("/")
    if (feed.isNullOrBlank() || !feed.matches(FEED_REGEX)) {
        respondText(
            text = "{\"error\":{\"code\":\"invalid_feed\",\"message\":\"feed must be alphanumeric (slashes / underscores / hyphens allowed), 1-64 chars\"}}",
            contentType = ContentType.Application.Json,
            status = HttpStatusCode.BadRequest,
        )
        return
    }
    val bytes = nsw.getVehiclePositionsRaw(feed = feed)
    respondBytes(bytes = bytes, contentType = ContentType.Application.ProtoBuf)
}
