package app.krail.bff.plugins

import app.krail.bff.model.ErrorDetails
import app.krail.bff.model.ErrorEnvelope
import app.krail.bff.util.Version
import app.krail.bff.util.correlationIdOrNull
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("app.krail.bff.plugins.VersionGate")

private const val HEADER_NAME = "X-Krail-Version"
private val EXEMPT_PATHS = setOf("/", "/health", "/ready")

/**
 * Rejects requests that don't carry an `X-Krail-Version` header (assumed not from the
 * real KRAIL app) or whose version is below `MIN_APP_VERSION`.
 *
 * - missing header → 400 `missing_version`
 * - malformed header → 400 `invalid_version`
 * - below floor → 426 `upgrade_required`
 *
 * Health endpoints and root are exempt so monitors / load balancers don't need the header.
 *
 * Config (env > yaml):
 *   MIN_APP_VERSION   /  bff.minAppVersion   — semver "MAJOR.MINOR.PATCH"; default 0.0.0
 */
fun Application.configureVersionGate() {
    val cfg = environment.config
    val raw = System.getenv("MIN_APP_VERSION")
        ?: cfg.propertyOrNull("bff.minAppVersion")?.getString()
        ?: "0.0.0"

    val minVersion = Version.parse(raw) ?: run {
        logger.warn("Invalid MIN_APP_VERSION '{}'; defaulting to 0.0.0", raw)
        Version(0, 0, 0)
    }
    val zero = Version(0, 0, 0)
    if (minVersion == zero) {
        logger.info("Version gate disabled (MIN_APP_VERSION = 0.0.0)")
        return
    }
    logger.info("Version gate active. Minimum app version: {}", minVersion)

    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        if (path in EXEMPT_PATHS) return@intercept

        val header = call.request.header(HEADER_NAME)
        if (header.isNullOrBlank()) {
            call.respondError(HttpStatusCode.BadRequest, "missing_version", "Missing $HEADER_NAME header")
            finish()
            return@intercept
        }

        val version = Version.parse(header)
        if (version == null) {
            call.respondError(HttpStatusCode.BadRequest, "invalid_version", "Invalid $HEADER_NAME format")
            finish()
            return@intercept
        }

        if (version < minVersion) {
            call.respondError(
                HttpStatusCode.UpgradeRequired,
                "upgrade_required",
                "App version $version is below minimum supported $minVersion"
            )
            finish()
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    message: String,
) {
    val envelope = ErrorEnvelope(
        error = ErrorDetails(code = code, message = message),
        correlationId = correlationIdOrNull()
    )
    respond(status, envelope)
}
