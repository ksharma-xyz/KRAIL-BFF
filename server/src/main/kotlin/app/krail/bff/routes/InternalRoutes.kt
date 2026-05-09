package app.krail.bff.routes

import app.krail.bff.config.NswConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("app.krail.bff.routes.InternalRoutes")

/**
 * Dev-only routes for the API tester / comparison tooling. Disabled by default;
 * enable with `BFF_DEV_PASSTHROUGH=true` in `local.properties` or env. Production
 * deploys must never set this — it intentionally proxies the BFF's NSW API key
 * to the browser so the dashboard can fire NSW-direct requests for comparison
 * without CORS pain.
 *
 * Endpoint:
 *   GET /internal/passthrough?url=<NSW URL>
 *     - URL **must** start with the configured NSW baseUrl (`nsw.baseUrl`).
 *       Anything else is rejected with 400 — guards against using the BFF as
 *       an open redirector / SSRF gadget even in dev.
 *     - Adds the `Authorization: apikey <NSW_API_KEY>` header server-side.
 *     - Returns the upstream response body verbatim, preserving Content-Type.
 *
 * Intended audience: docs/tools/api-tester.html and curl-from-localhost only.
 * Smoke-runs against this in CI / production deployments are a misconfiguration.
 */
fun Application.configureInternalRoutes() {
    val enabled = (System.getenv("BFF_DEV_PASSTHROUGH")
        ?: environment.config.propertyOrNull("bff.devPassthrough")?.getString()
        ?: "false").equals("true", ignoreCase = true)

    if (!enabled) {
        logger.debug("Internal /internal/passthrough disabled (set BFF_DEV_PASSTHROUGH=true in local dev only)")
        return
    }

    val nswConfig by inject<NswConfig>()
    val httpClient by inject<HttpClient>()
    val nswPrefix = nswConfig.baseUrl.trimEnd('/')

    logger.warn("⚠ Internal /internal/passthrough ENABLED — DEV ONLY. Never enable on a public deploy.")

    routing {
        get("/internal/passthrough") {
            val upstream = call.request.queryParameters["url"]
            if (upstream.isNullOrBlank()) {
                return@get call.respondText(
                    """{"error":{"code":"missing_url","message":"Provide ?url=<NSW URL>"}}""",
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.BadRequest,
                )
            }
            // Strict prefix check — only the configured NSW host. No wildcards, no other hosts.
            if (!upstream.startsWith("$nswPrefix/")) {
                logger.warn("Rejected passthrough to non-NSW URL: {}", upstream)
                return@get call.respondText(
                    """{"error":{"code":"invalid_upstream","message":"URL must start with the configured NSW baseUrl"}}""",
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.BadRequest,
                )
            }

            try {
                val response = httpClient.get(upstream) {
                    headers.append("Authorization", "apikey ${nswConfig.apiKey}")
                }
                val ctRaw = response.headers["Content-Type"] ?: "application/octet-stream"
                val ct = runCatching { ContentType.parse(ctRaw) }.getOrNull() ?: ContentType.Application.OctetStream
                val isText = ct.match(ContentType.Application.Json) ||
                    ct.match(ContentType.Application.Xml) ||
                    ct.contentType == "text"

                val statusCode = HttpStatusCode.fromValue(response.status.value)
                if (isText) {
                    call.respondText(response.bodyAsText(), ct, statusCode)
                } else {
                    call.respondBytes(response.body<ByteArray>(), ct, statusCode)
                }
            } catch (e: Throwable) {
                logger.warn("Passthrough to {} failed: {}", upstream, e.message)
                call.respondText(
                    """{"error":{"code":"passthrough_failed","message":"${e.message?.replace("\"", "'")}"}}""",
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.BadGateway,
                )
            }
        }
    }
}
