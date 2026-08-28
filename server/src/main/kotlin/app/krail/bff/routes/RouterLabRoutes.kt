package app.krail.bff.routes

import app.krail.bff.region.RegionRegistry
import app.krail.bff.region.RegionTripService
import app.krail.bff.util.boolean
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("app.krail.bff.routes.RouterLabRoutes")

// Allowlist for stop-name search text: Unicode letters (with combining
// marks — Ōtāhuhu, Anděl, Möckernbrücke), digits, spaces and the punctuation
// stop names actually use (. , ' & / # ( ) _ : -). Reject, don't sanitize.
private val SEARCH_QUERY_REGEX = Regex("^[\\p{L}\\p{M}0-9 .,'&/#()_:-]{1,64}$")

private const val INVALID_QUERY_BODY =
    "{\"error\":{\"code\":\"invalid_query\"," +
        "\"message\":\"q must be 1-64 chars: letters, digits, spaces, .,'&/#()_:-\"}}"

/**
 * Router Lab — dev-only journey-planner debug dashboard. Same gate as
 * /internal/passthrough (`BFF_DEV_PASSTHROUGH=true`); production must never
 * set it, so none of this is reachable in prod config.
 *
 * Endpoints:
 * - GET /internal/router-lab                 the dashboard (self-contained HTML)
 * - GET /internal/router-lab/regions         BFF-routed regions + loaded flags;
 *   the page builds its region dropdown from this (NSW is appended client-side)
 * - GET /internal/{code}/stops/search?q=     diacritic-insensitive stop-name
 *   autocomplete over that region's loaded RouterModel; registers per region
 *   whose snapshot loaded.
 *
 * The lab calls only BFF endpoints — the NSW key never reaches the browser.
 */
fun Application.configureRouterLabRoutes() {
    val enabled = environment.config.boolean("BFF_DEV_PASSTHROUGH", "bff.devPassthrough", false)
    if (!enabled) {
        logger.debug("Router Lab disabled (set BFF_DEV_PASSTHROUGH=true in local dev only)")
        return
    }
    logger.warn("⚠ /internal/router-lab ENABLED — DEV ONLY. Never enable on a public deploy.")
    routerLabRoutes(attributes.getOrNull(RegionTripServicesKey) ?: emptyMap())
}

internal fun Application.routerLabRoutes(services: Map<String, RegionTripService>) {
    val html = checkNotNull(
        RouterLabPage::class.java.classLoader.getResourceAsStream("router-lab/index.html")
    ) { "router-lab/index.html missing from resources" }.readBytes().decodeToString()

    routing {
        get("/internal/router-lab") {
            call.respondText(html, ContentType.Text.Html)
        }

        get("/internal/router-lab/regions") {
            val body = buildJsonArray {
                for (spec in RegionRegistry.all) {
                    add(buildJsonObject {
                        put("code", spec.code)
                        put("name", spec.displayName)
                        put("prefix", spec.idPrefix)
                        put("tz", spec.zone.id)
                        put("loaded", services.containsKey(spec.code))
                    })
                }
            }
            call.respondText(body.toString(), ContentType.Application.Json)
        }

        for ((code, service) in services) {
            stopSearchRoute("/internal/$code/stops/search") { q ->
                service.searchStops(q).map { SearchHit(it.id, it.name, it.lat, it.lon) }
            }
        }
    }
}

/** One allowlist-validated stop-name search endpoint over a loaded model. */
private fun Route.stopSearchRoute(path: String, search: (String) -> List<SearchHit>) {
    get(path) {
        val q = call.request.queryParameters["q"]
        if (q.isNullOrBlank() || !q.matches(SEARCH_QUERY_REGEX)) {
            return@get call.respondText(
                text = INVALID_QUERY_BODY,
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.BadRequest,
            )
        }
        val body = buildJsonArray {
            search(q).forEach { hit ->
                add(buildJsonObject {
                    put("id", hit.id)
                    put("name", hit.name)
                    put("lat", hit.lat)
                    put("lon", hit.lon)
                })
            }
        }
        call.respondText(body.toString(), ContentType.Application.Json)
    }
}

private class SearchHit(val id: String, val name: String, val lat: Double, val lon: Double)

private object RouterLabPage
