package app.krail.bff.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.path
import io.ktor.server.response.*
import java.util.concurrent.atomic.AtomicLong

private class GlobalRateLimiter(
    private val capacity: Long,
    private val refillPerSecond: Long
) {
    private val tokens = AtomicLong(capacity)
    private val lastRefillMs = AtomicLong(System.currentTimeMillis())

    fun allow(): Boolean {
        refill()
        while (true) {
            val current = tokens.get()
            if (current <= 0) return false
            if (tokens.compareAndSet(current, current - 1)) return true
        }
    }

    private fun refill() {
        val now = System.currentTimeMillis()
        val last = lastRefillMs.get()
        if (now <= last) return
        val elapsedMs = now - last
        val toAdd = (elapsedMs * refillPerSecond) / 1000
        if (toAdd > 0) {
            if (lastRefillMs.compareAndSet(last, now)) {
                tokens.updateAndGet { cur ->
                    val newTokens = cur + toAdd
                    if (newTokens > capacity) capacity else newTokens
                }
            }
        }
    }
}

fun Application.configureHTTP() {
    // Global rate limiter settings (env > application.yaml > defaults)
    val config = environment.config
    val rps = (System.getenv("BFF_RATE_LIMIT_RPS")?.toLongOrNull()
        ?: config.propertyOrNull("bff.rateLimit.rps")?.getString()?.toLongOrNull()) ?: 3L
    val burst = (System.getenv("BFF_RATE_LIMIT_BURST")?.toLongOrNull()
        ?: config.propertyOrNull("bff.rateLimit.burst")?.getString()?.toLongOrNull()) ?: 3L

    val limiter = GlobalRateLimiter(capacity = burst, refillPerSecond = rps)

    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        // Bypass limiter for health endpoints
        if (path == "/health" || path == "/ready") return@intercept

        if (!limiter.allow()) {
            call.response.headers.append("Retry-After", "1")
            val body = mapOf(
                "success" to false,
                "error" to mapOf(
                    "code" to "rate_limited",
                    "message" to "Too Many Requests"
                )
            )
            call.respond(HttpStatusCode.TooManyRequests, body)
            finish()
        }
    }
}
