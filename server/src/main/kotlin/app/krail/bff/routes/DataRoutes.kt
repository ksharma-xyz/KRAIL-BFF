package app.krail.bff.routes

import app.krail.bff.model.ErrorDetails
import app.krail.bff.model.ErrorEnvelope
import app.krail.bff.util.correlationIdOrNull
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("app.krail.bff.routes.DataRoutes")

/**
 * Static-data endpoints — manifests for the bundled datasets the KRAIL app
 * consumes (stops + routes today; more later).
 *
 * Each `/v1/data/{dataset}/manifest` 302-redirects to the published manifest
 * URL configured via env (`STOPS_MANIFEST_URL`, `ROUTES_MANIFEST_URL`).
 * The app fetches the manifest, compares the `version`, and downloads the
 * referenced `.pb` only if newer. Returning a redirect keeps the BFF's data
 * path zero-compute; Cloudflare caches the redirect itself.
 */
fun Application.configureDataRoutes() {
    val stopsManifestUrl = System.getenv("STOPS_MANIFEST_URL")
        ?: environment.config.propertyOrNull("data.stops.manifestUrl")?.getString()
    val routesManifestUrl = System.getenv("ROUTES_MANIFEST_URL")
        ?: environment.config.propertyOrNull("data.routes.manifestUrl")?.getString()

    routing {
        route("/v1/data/stops") {
            get("/manifest") { call.serveManifestRedirect("STOPS_MANIFEST_URL", stopsManifestUrl) }
        }
        route("/v1/data/routes") {
            get("/manifest") { call.serveManifestRedirect("ROUTES_MANIFEST_URL", routesManifestUrl) }
        }
    }
}

private suspend fun ApplicationCall.serveManifestRedirect(envName: String, manifestUrl: String?) {
    if (manifestUrl.isNullOrBlank()) {
        logger.debug("{} not configured; returning 404", envName)
        response.headers.append("Cache-Control", "no-store")
        val envelope = ErrorEnvelope(
            error = ErrorDetails(
                code = "manifest_not_configured",
                message = "Manifest URL not configured ($envName)",
            ),
            correlationId = correlationIdOrNull(),
        )
        respond(HttpStatusCode.NotFound, envelope)
        return
    }
    response.headers.append("Cache-Control", "public, max-age=300")
    respondRedirect(manifestUrl, permanent = false)
}
