package app.krail.bff.plugins

import io.ktor.server.application.*
import io.ktor.util.*
import org.slf4j.MDC
import java.util.*


object Correlation {
    val Key = AttributeKey<String>("CorrelationId")
}

fun Application.configureCorrelation() {
    intercept(ApplicationCallPipeline.Setup) {
        val incoming = call.request.headers[Headers.REQUEST_ID]?.takeIf { it.isNotBlank() }
        val correlationId = incoming ?: UUID.randomUUID().toString()

        // Store on call attributes for downstream retrieval
        call.attributes.put(Correlation.Key, correlationId)

        // Put in MDC for logging
        MDC.put("correlationId", correlationId)

        try {
            proceed()
        } finally {
            // Ensure header always present on responses
            call.response.headers.append(Headers.REQUEST_ID, correlationId, safeOnly = false)
            MDC.remove("correlationId")
        }
    }
}
