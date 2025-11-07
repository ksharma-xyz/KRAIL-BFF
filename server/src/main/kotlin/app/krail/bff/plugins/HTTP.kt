package app.krail.bff.plugins

import app.krail.bff.model.ErrorDetails
import app.krail.bff.model.ErrorEnvelope
import app.krail.bff.util.correlationIdOrNull
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.path
import io.ktor.server.response.*

/**
 * Global rate limiter using Token Bucket algorithm.
 *
 * Token Bucket Algorithm:
 * - Think of a bucket that holds "tokens" (like coins)
 * - Each request needs 1 token to be allowed
 * - Tokens are automatically refilled at a steady rate
 * - If bucket is empty (no tokens), requests are rejected
 *
 * Example:
 * - Capacity: 10 tokens (burst - allows 10 quick requests)
 * - Refill rate: 3 tokens/second (3 requests per second sustained)
 *
 * Scenario:
 * 1. Client makes 10 requests instantly → All allowed (uses all 10 tokens)
 * 2. Client makes 11th request → Rejected (no tokens left)
 * 3. After 1 second → 3 tokens refilled (can make 3 more requests)
 * 4. After 3.33 seconds → Bucket full again (10 tokens)
 *
 * This prevents:
 * - DDoS attacks (too many requests at once)
 * - Resource exhaustion (overwhelming the server)
 * - Unfair usage (one client hogging all resources)
 */
private class GlobalRateLimiter(
    private val capacity: Long,        // Max tokens in bucket (burst capacity)
    private val refillPerSecond: Long  // How many tokens to add per second
) {
    // Current number of available tokens (thread-safe atomic counter)
    private val tokens = java.util.concurrent.atomic.AtomicLong(capacity)

    // Last time we refilled tokens (timestamp in milliseconds)
    private val lastRefillMs = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

    /**
     * Check if a request is allowed.
     *
     * @return true if request is allowed (token consumed), false if rate limited
     */
    fun allow(): Boolean {
        refill() // First, add any tokens accumulated since last check

        // Try to consume a token (atomic operation for thread safety)
        while (true) {
            val current = tokens.get()
            if (current <= 0) return false // No tokens left - rate limited!

            // Try to decrement token count (compareAndSet ensures thread safety)
            if (tokens.compareAndSet(current, current - 1)) return true
        }
    }

    /**
     * Refill tokens based on elapsed time.
     *
     * Tokens refill automatically over time at the specified rate.
     * Example: If 2 seconds passed and refillPerSecond=3, add 6 tokens.
     */
    private fun refill() {
        val now = System.currentTimeMillis()
        val last = lastRefillMs.get()

        if (now <= last) return // No time elapsed, nothing to refill

        val elapsedMs = now - last // Time since last refill (milliseconds)
        val toAdd = (elapsedMs * refillPerSecond) / 1000 // Convert to tokens

        if (toAdd > 0) {
            // Update last refill time (only one thread wins if concurrent)
            if (lastRefillMs.compareAndSet(last, now)) {
                // Add tokens, but don't exceed capacity
                tokens.updateAndGet { cur ->
                    val newTokens = cur + toAdd
                    if (newTokens > capacity) capacity else newTokens
                }
            }
        }
    }
}

fun Application.configureHTTP() {
    // ============================================
    // CORS Configuration
    // ============================================
    // Allows browser-based clients to make requests to this API
    //
    // ⚠️ SECURITY WARNING FOR PRODUCTION:
    // The current configuration uses anyHost() which allows ALL origins.
    // This is ONLY safe for local development!
    //
    // Before deploying to production, you MUST change to:
    //
    //   install(CORS) {
    //       allowHost("yourdomain.com", schemes = listOf("https"))
    //       allowHost("app.yourdomain.com", schemes = listOf("https"))
    //       // DO NOT use anyHost() in production!
    //
    //       allowMethod(HttpMethod.Get)
    //       allowMethod(HttpMethod.Post)
    //       // ... rest of config
    //   }
    //
    // Why? anyHost() allows ANY website to make requests to your API,
    // which can lead to:
    // - Cross-Site Request Forgery (CSRF) attacks
    // - Data theft from malicious websites
    // - Unauthorized API access
    //
    // ============================================
    install(CORS) {
        // ⚠️ DEVELOPMENT ONLY - Replace with specific domains in production!
        anyHost()

        // Allow common HTTP methods
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Patch)

        // Allow common headers
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.Accept)
        allowHeader("X-Request-Id")

        // Expose headers for clients
        exposeHeader(HttpHeaders.ContentType)
        exposeHeader("X-Request-Id")

        // Allow credentials (cookies, auth headers)
        allowCredentials = true

        // Cache preflight requests for 1 hour
        // (Reduces OPTIONS requests from browsers)
        maxAgeInSeconds = 3600
    }

    // ============================================
    // Global Rate Limiter
    // ============================================
    // Prevents abuse by limiting requests per second
    //
    // Configuration (in order of priority):
    // 1. Environment variables: BFF_RATE_LIMIT_RPS, BFF_RATE_LIMIT_BURST
    // 2. application.yaml: bff.rateLimit.rps, bff.rateLimit.burst
    // 3. Defaults: 3 requests/second, burst of 3
    //
    // Example values:
    // - rps=3, burst=3: Allows 3 req/sec sustained, max 3 at once
    // - rps=10, burst=20: Allows 10 req/sec sustained, max 20 at once
    //
    // Health endpoints (/health, /ready) bypass rate limiting
    // ============================================
    val config = environment.config

    // Read rate limit settings from environment or config
    val rps = (System.getenv("BFF_RATE_LIMIT_RPS")?.toLongOrNull()
        ?: config.propertyOrNull("bff.rateLimit.rps")?.getString()?.toLongOrNull()) ?: 3L
    val burst = (System.getenv("BFF_RATE_LIMIT_BURST")?.toLongOrNull()
        ?: config.propertyOrNull("bff.rateLimit.burst")?.getString()?.toLongOrNull()) ?: 3L

    // Create the rate limiter with configured values
    val limiter = GlobalRateLimiter(capacity = burst, refillPerSecond = rps)

    // Intercept all requests to check rate limiting
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()

        // Bypass limiter for health endpoints
        // (We want health checks to always work, even under load)
        if (path == "/health" || path == "/ready") return@intercept

        // Check if request is allowed (consumes a token if available)
        if (!limiter.allow()) {
            // Rate limit exceeded!
            // Tell client to retry after 1 second
            call.response.headers.append("Retry-After", "1")

            // Return 429 Too Many Requests error
            val envelope = ErrorEnvelope(
                error = ErrorDetails(
                    code = "rate_limited",
                    message = "Too Many Requests"
                ),
                correlationId = call.correlationIdOrNull()
            )
            call.respond(HttpStatusCode.TooManyRequests, envelope)

            // Stop processing this request
            finish()
        }

        // Request allowed - continue to next pipeline stage
    }
}
