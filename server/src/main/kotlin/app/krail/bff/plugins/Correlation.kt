package app.krail.bff.plugins

import io.ktor.server.application.*
import io.ktor.util.*
import org.slf4j.MDC
import java.util.*


object Correlation {
    val Key = AttributeKey<String>("CorrelationId")
}

private fun isValidUuid(value: String?): Boolean {
    if (value.isNullOrBlank()) return false
    // quick length check to avoid pathological inputs
    if (value.length > 64) return false
    return try {
        UUID.fromString(value)
        true
    } catch (_: IllegalArgumentException) {
        false
    }
}

fun Application.configureCorrelation() {
    intercept(ApplicationCallPipeline.Setup) {
        val provided = call.request.headers[Headers.REQUEST_ID]
        val correlationId = if (isValidUuid(provided)) provided!! else UUID.randomUUID().toString()

        // Store on call attributes for downstream retrieval
        call.attributes.put(Correlation.Key, correlationId)

        // Put in MDC for logging (deviceId is handled elsewhere and not logged)
        MDC.put("correlationId", correlationId)

        try {
            proceed()
        } finally {
            // Ensure header always present on responses; safeOnly=true guards against header injection
            call.response.headers.append(Headers.REQUEST_ID, correlationId, safeOnly = true)
            MDC.remove("correlationId")
        }
    }
}
