package app.krail.bff.routes

import app.krail.bff.util.boolean
import app.krail.bff.vic.VicTripService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("app.krail.bff.routes.RouterLabRoutes")

// Allowlist for stop-name search text: letters, digits, spaces and the
// punctuation VIC stop names actually use (. , ' & / # ( ) _ : -). Reject,
// don't sanitize.
private val SEARCH_QUERY_REGEX = Regex("^[A-Za-z0-9 .,'&/#()_:-]{1,64}$")

private const val INVALID_QUERY_BODY =
    "{\"error\":{\"code\":\"invalid_query\"," +
        "\"message\":\"q must be 1-64 chars: letters, digits, spaces, .,'&/#()_:-\"}}"

/**
 * Router Lab — dev-only journey-planner debug dashboard. Same gate as
 * /internal/passthrough (`BFF_DEV_PASSTHROUGH=true`); production must never
 * set it, so none of this is reachable in prod config.
 *
 * Endpoints:
 * - GET /internal/router-lab              the dashboard (self-contained HTML)
 * - GET /internal/vic/stops/search?q=     stop-name autocomplete over the
 *   loaded VIC RouterModel; registers only when the VIC model loaded.
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
    routerLabRoutes(attributes.getOrNull(VicTripServiceKey))
}

internal fun Application.routerLabRoutes(vicService: VicTripService?) {
    val html = checkNotNull(
        RouterLabPage::class.java.classLoader.getResourceAsStream("router-lab/index.html")
    ) { "router-lab/index.html missing from resources" }.readBytes().decodeToString()

    routing {
        get("/internal/router-lab") {
            call.respondText(html, ContentType.Text.Html)
        }

        if (vicService != null) {
            get("/internal/vic/stops/search") {
                val q = call.request.queryParameters["q"]
                if (q.isNullOrBlank() || !q.matches(SEARCH_QUERY_REGEX)) {
                    return@get call.respondText(
                        text = INVALID_QUERY_BODY,
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.BadRequest,
                    )
                }
                val body = buildJsonArray {
                    vicService.searchStops(q).forEach { hit ->
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
    }
}

private object RouterLabPage
