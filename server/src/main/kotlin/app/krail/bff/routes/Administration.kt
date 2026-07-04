package app.krail.bff.routes

import app.krail.bff.client.nsw.NswClient
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

    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "up"))
        }
        get("/ready") {
            val upstreamOk = upstreamOkCached()
            if (upstreamOk) {
                call.respond(HttpStatusCode.OK, mapOf("status" to "ready", "nsw" to "up"))
            } else {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to "degraded", "nsw" to "down"))
            }
        }
    }
}
