package app.krail.bff.plugins

import app.krail.bff.model.ErrorDetails
import app.krail.bff.model.ErrorEnvelope
import app.krail.bff.util.TokenBucket
import app.krail.bff.util.correlationIdOrNull
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.path
import io.ktor.server.response.*

private val EXEMPT_PATHS = setOf("/", "/health", "/ready")

fun Application.configureHTTP() {
    val config = environment.config

    // CORS — env-driven allowlist.
    //
    // BFF_CORS_ORIGINS env var (or bff.cors.origins yaml) is a comma-separated
    // list of fully-qualified origins, e.g.
    //   "https://krail.app,http://localhost:3000"
    //
    // Empty = no cross-origin requests allowed (safe production default).
    val corsOrigins = (System.getenv("BFF_CORS_ORIGINS")
        ?: config.propertyOrNull("bff.cors.origins")?.getString().orEmpty())
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }

    install(CORS) {
        corsOrigins.forEach { origin ->
            val parts = origin.split("://", limit = 2)
            if (parts.size == 2 && parts[1].isNotBlank()) {
                allowHost(host = parts[1], schemes = listOf(parts[0]))
            }
        }

        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Patch)

        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.Accept)
        allowHeader("X-Request-Id")
        allowHeader("X-Krail-Version")

        exposeHeader(HttpHeaders.ContentType)
        exposeHeader("X-Request-Id")

        allowCredentials = corsOrigins.isNotEmpty()
        maxAgeInSeconds = 3600
    }

    // Global aggregate rate limiter — backstop only. Per-IP limiter is the primary
    // defence (see configurePerIpRateLimit). Defaults are intentionally generous
    // because per-IP catches the common case; this only fires on cross-IP floods.
    //
    // Tunables (env > yaml):
    //   BFF_RATE_LIMIT_RPS    / bff.rateLimit.rps    — sustained req/sec, default 50
    //   BFF_RATE_LIMIT_BURST  / bff.rateLimit.burst  — burst capacity, default 100
    //
    // Health endpoints and root bypass rate limiting.
    val rps = (System.getenv("BFF_RATE_LIMIT_RPS")?.toLongOrNull()
        ?: config.propertyOrNull("bff.rateLimit.rps")?.getString()?.toLongOrNull()) ?: 50L
    val burst = (System.getenv("BFF_RATE_LIMIT_BURST")?.toLongOrNull()
        ?: config.propertyOrNull("bff.rateLimit.burst")?.getString()?.toLongOrNull()) ?: 100L

    val limiter = TokenBucket(capacity = burst, refillPerSecond = rps)

    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        if (path in EXEMPT_PATHS) return@intercept

        if (!limiter.allow()) {
            call.response.headers.append("Retry-After", "1")
            val envelope = ErrorEnvelope(
                error = ErrorDetails(code = "rate_limited", message = "Too Many Requests"),
                correlationId = call.correlationIdOrNull()
            )
            call.respond(HttpStatusCode.TooManyRequests, envelope)
            finish()
        }
    }
}
