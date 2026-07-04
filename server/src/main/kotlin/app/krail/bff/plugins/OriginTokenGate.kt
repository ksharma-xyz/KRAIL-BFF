package app.krail.bff.plugins

import app.krail.bff.model.ErrorDetails
import app.krail.bff.model.ErrorEnvelope
import app.krail.bff.util.correlationIdOrNull
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("app.krail.bff.plugins.OriginTokenGate")

private const val HEADER_NAME = "CF-Origin-Token"
private val EXEMPT_PATHS = setOf("/", "/health", "/ready")

/**
 * Reject requests that don't carry the shared `CF-Origin-Token` header. Cloudflare
 * is configured (via Transform Rules) to add this header when proxying to origin;
 * direct hits to the origin IP that bypass Cloudflare won't have it.
 *
 * Combined with the DO firewall (allow only Cloudflare's IP ranges), this pins
 * traffic to come through Cloudflare. The header is the second line in case
 * Cloudflare's IP ranges are spoofed somehow.
 *
 * Opt-in via env: when `CF_ORIGIN_TOKEN` (or `bff.cfOriginToken`) is unset/blank,
 * the gate is disabled entirely (intended for local dev and tests).
 *
 * Health and root paths are exempt so DO's health probe and curl smoke tests
 * still work without the header.
 */
fun Application.configureOriginTokenGate() {
    val expected = System.getenv("CF_ORIGIN_TOKEN")
        ?: environment.config.propertyOrNull("bff.cfOriginToken")?.getString()

    if (expected.isNullOrBlank()) {
        logger.info("Origin token gate disabled (CF_ORIGIN_TOKEN unset)")
        return
    }
    logger.info("Origin token gate active")

    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        if (path in EXEMPT_PATHS) return@intercept

        val actual = call.request.header(HEADER_NAME)
        // Constant-time comparison — a plain != short-circuits on the first
        // differing byte and can leak token prefixes through response timing.
        val matches = actual != null &&
            java.security.MessageDigest.isEqual(actual.toByteArray(), expected.toByteArray())
        if (!matches) {
            val envelope = ErrorEnvelope(
                error = ErrorDetails(code = "forbidden", message = "Forbidden"),
                correlationId = call.correlationIdOrNull()
            )
            call.respond(HttpStatusCode.Forbidden, envelope)
            finish()
        }
    }
}
