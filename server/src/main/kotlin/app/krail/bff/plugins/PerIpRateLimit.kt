package app.krail.bff.plugins

import app.krail.bff.model.ErrorDetails
import app.krail.bff.model.ErrorEnvelope
import app.krail.bff.util.TokenBucket
import app.krail.bff.util.clientIp
import app.krail.bff.util.correlationIdOrNull
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.respond
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val SWEEP_INTERVAL_MS = 60_000L
private val EXEMPT_PATHS = setOf("/", "/health", "/ready")

/**
 * Per-IP token-bucket rate limit. Cloudflare is the first line; this is the BFF's
 * own safety net for when an edge rule is misconfigured or a request bypasses the
 * edge entirely.
 *
 * Tunables (env > yaml):
 *   BFF_PER_IP_RPS      / bff.perIp.rps      — sustained req/sec per IP, default 5
 *   BFF_PER_IP_BURST    / bff.perIp.burst    — burst capacity per IP, default 10
 *   BFF_PER_IP_MAX      / bff.perIp.maxIps   — soft cap on tracked IPs, default 10000
 *
 * Health endpoints and root are exempt.
 */
fun Application.configurePerIpRateLimit() {
    val cfg = environment.config
    val rps = (System.getenv("BFF_PER_IP_RPS")?.toLongOrNull()
        ?: cfg.propertyOrNull("bff.perIp.rps")?.getString()?.toLongOrNull()) ?: 5L
    val burst = (System.getenv("BFF_PER_IP_BURST")?.toLongOrNull()
        ?: cfg.propertyOrNull("bff.perIp.burst")?.getString()?.toLongOrNull()) ?: 10L
    val maxIps = (System.getenv("BFF_PER_IP_MAX")?.toIntOrNull()
        ?: cfg.propertyOrNull("bff.perIp.maxIps")?.getString()?.toIntOrNull()) ?: 10_000

    val limiter = PerIpLimiter(rps = rps, burst = burst, maxIps = maxIps)

    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        if (path in EXEMPT_PATHS) return@intercept

        val ip = call.clientIp()
        if (!limiter.allow(ip)) {
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

private class PerIpLimiter(
    private val rps: Long,
    private val burst: Long,
    private val maxIps: Int,
) {
    private val buckets = ConcurrentHashMap<String, TokenBucket>()
    private val lastSweepMs = AtomicLong(System.currentTimeMillis())

    fun allow(ip: String): Boolean {
        maybeSweep()
        val bucket = buckets.computeIfAbsent(ip) { TokenBucket(capacity = burst, refillPerSecond = rps) }
        return bucket.allow()
    }

    private fun maybeSweep() {
        val now = System.currentTimeMillis()
        val last = lastSweepMs.get()
        if (now - last < SWEEP_INTERVAL_MS) return
        if (!lastSweepMs.compareAndSet(last, now)) return

        // Drop buckets that are full (== nobody's spent a token in capacity/rps seconds).
        // Bounds memory without an LRU.
        buckets.entries.removeIf { it.value.isFull() }
        // Hard cap: under a spoofed-IP flood the buckets are all actively draining,
        // so the sweep above removes nothing. Resetting momentarily refills everyone's
        // bucket, which is a far smaller failure than unbounded map growth.
        if (buckets.size > maxIps) {
            buckets.clear()
        }
    }
}
