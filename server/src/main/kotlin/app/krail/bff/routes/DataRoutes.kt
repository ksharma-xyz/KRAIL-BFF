package app.krail.bff.routes

import app.krail.bff.model.ErrorDetails
import app.krail.bff.model.ErrorEnvelope
import app.krail.bff.util.correlationIdOrNull
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("app.krail.bff.routes.DataRoutes")

/**
 * Static-data endpoints — manifests and dataset URLs.
 *
 * `/v1/data/stops/manifest` redirects to the latest stops dataset manifest,
 * which lives in GitHub Releases (or wherever `STOPS_MANIFEST_URL` points).
 * The app fetches the manifest, compares the `version`, and downloads the
 * referenced `.pb` only if newer.
 *
 * Returning a redirect (rather than fetching+re-emitting the manifest) keeps
 * the BFF's data path zero-compute. Cloudflare caches the redirect itself.
 */
fun Application.configureDataRoutes() {
    val manifestUrl = System.getenv("STOPS_MANIFEST_URL")
        ?: environment.config.propertyOrNull("data.stops.manifestUrl")?.getString()

    routing {
        route("/v1/data/stops") {
            get("/manifest") {
                if (manifestUrl.isNullOrBlank()) {
                    logger.debug("STOPS_MANIFEST_URL not configured; returning 404")
                    call.response.headers.append("Cache-Control", "no-store")
                    val envelope = ErrorEnvelope(
                        error = ErrorDetails(code = "manifest_not_configured", message = "Stops manifest URL not configured"),
                        correlationId = call.correlationIdOrNull(),
                    )
                    call.respond(HttpStatusCode.NotFound, envelope)
                    return@get
                }
                // 302 — the upstream manifest may be re-published with the same URL
                // pointing at a newer version. Don't cache 301 (permanent) for that reason.
                call.response.headers.append("Cache-Control", "public, max-age=300")
                call.respondRedirect(manifestUrl, permanent = false)
            }
        }
    }
}
