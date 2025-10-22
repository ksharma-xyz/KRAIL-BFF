package app.krail.bff.plugins

import app.krail.bff.model.MobileContext
import io.ktor.server.application.*
import io.ktor.util.*
import org.slf4j.MDC

object MobileAttributes {
    val Key = AttributeKey<MobileContext>("MobileContext")
}

private fun sanitizeHeader(value: String?, maxLen: Int = 256): String? {
    if (value == null) return null
    // Strip control characters (includes CR/LF, tabs, etc.) and DEL
    val noCtrl = value.replace(Regex("[\\p{Cntrl}]"), "")
    // Cap length to avoid log bloat
    val capped = if (noCtrl.length > maxLen) noCtrl.substring(0, maxLen) else noCtrl
    return capped.ifBlank { null }
}

fun Application.configureMobileAnalytics() {
    intercept(ApplicationCallPipeline.Setup) {
        val headers = call.request.headers
        val ctx = MobileContext(
            deviceId = sanitizeHeader(headers[Headers.DEVICE_ID]),
            deviceModel = sanitizeHeader(headers[Headers.DEVICE_MODEL]),
            osName = sanitizeHeader(headers[Headers.OS_NAME]),
            osVersion = sanitizeHeader(headers[Headers.OS_VERSION]),
            appVersion = sanitizeHeader(headers[Headers.APP_VERSION]),
            clientRegion = sanitizeHeader(headers[Headers.CLIENT_REGION]),
            networkType = sanitizeHeader(headers[Headers.NETWORK_TYPE])
        )

        // Store on call attributes for downstream access
        call.attributes.put(MobileAttributes.Key, ctx)

        // Put selected fields into MDC for structured logging (never log deviceId)
        ctx.deviceModel?.let { MDC.put("deviceModel", it) }
        ctx.osName?.let { MDC.put("osName", it) }
        ctx.osVersion?.let { MDC.put("osVersion", it) }
        ctx.appVersion?.let { MDC.put("appVersion", it) }
        ctx.clientRegion?.let { MDC.put("clientRegion", it) }
        ctx.networkType?.let { MDC.put("networkType", it) }

        try {
            proceed()
        } finally {
            // Clean up MDC keys (deviceId was never set)
            MDC.remove("deviceModel")
            MDC.remove("osName")
            MDC.remove("osVersion")
            MDC.remove("appVersion")
            MDC.remove("clientRegion")
            MDC.remove("networkType")
        }
    }
}
