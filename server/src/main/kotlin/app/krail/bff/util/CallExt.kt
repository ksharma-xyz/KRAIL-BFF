package app.krail.bff.util

import app.krail.bff.plugins.Correlation
import io.ktor.server.application.*

/**
 * Safely retrieves the correlation ID from call attributes.
 * Returns null if not found (e.g., if correlation plugin not installed or call not in request context).
 */
fun ApplicationCall.correlationIdOrNull(): String? = try {
    attributes.getOrNull(Correlation.Key)
} catch (_: Throwable) {
    null
}

