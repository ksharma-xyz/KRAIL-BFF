package app.krail.bff.routes

import app.krail.bff.client.nsw.NswClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.ktor.ext.inject

// /ready is exempt from the origin-token gate and rate limiters (probes can't send
// headers), so without a cache every anonymous hit would fire a live NSW request —
// an unauthenticated upstream-call amplifier. Cache the result briefly instead;
// probes run every ~10s, so 30s staleness is fine.
private const val READY_CACHE_MS = 30_000L

fun Application.configureAdministration() {
    val nswClient by inject<NswClient>()

    var cachedOk = false
    var cachedAtMs = 0L
    val readyLock = Mutex()

    suspend fun upstreamOkCached(): Boolean {
        val now = System.currentTimeMillis()
        if (now - cachedAtMs < READY_CACHE_MS) return cachedOk
        return readyLock.withLock {
            val t = System.currentTimeMillis()
            if (t - cachedAtMs < READY_CACHE_MS) return@withLock cachedOk
            cachedOk = try {
                nswClient.healthCheck()
            } catch (_: Throwable) {
                false
            }
            cachedAtMs = t
            cachedOk
        }
    }

    // GIT_SHA is set by CI/local Docker builds (build arg). DO's builder strips
    // .git (see .dockerignore) so prod reports "dev" — the post-deploy smoke test
    // keys off startedAt instead: a fresh timestamp after a push ⇒ new container.
    val buildVersion = System.getenv("GIT_SHA")?.takeIf { it.isNotBlank() } ?: "dev"
    val startedAt = java.time.Instant.now().toString()

    // Explicit JSON bodies — kotlinx ContentNegotiation renders a plain mapOf()
    // as "{}" (no serializer for Map<String, String> at runtime), which the
    // smoke test and synthetic monitor would read as missing fields.
    val healthBody =
        """{"status":"up","version":"$buildVersion","startedAt":"$startedAt"}"""

    routing {
        get("/health") {
            call.respondText(healthBody, ContentType.Application.Json, HttpStatusCode.OK)
        }
        get("/ready") {
            if (upstreamOkCached()) {
                call.respondText(
                    """{"status":"ready","nsw":"up"}""",
                    ContentType.Application.Json, HttpStatusCode.OK
                )
            } else {
                call.respondText(
                    """{"status":"degraded","nsw":"down"}""",
                    ContentType.Application.Json, HttpStatusCode.ServiceUnavailable
                )
            }
        }
    }
}
