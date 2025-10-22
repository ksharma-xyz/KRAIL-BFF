package app.krail.bff.plugins

import app.krail.bff.model.MobileContext
import io.ktor.server.application.*
import io.ktor.util.*
import org.slf4j.MDC

object MobileAttributes {
    val Key = AttributeKey<MobileContext>("MobileContext")
}

fun Application.configureMobileAnalytics() {
    intercept(ApplicationCallPipeline.Setup) {
        val headers = call.request.headers
        val ctx = MobileContext(
            deviceId = headers[Headers.DEVICE_ID],
            deviceModel = headers[Headers.DEVICE_MODEL],
            osName = headers[Headers.OS_NAME],
            osVersion = headers[Headers.OS_VERSION],
            appVersion = headers[Headers.APP_VERSION],
            clientRegion = headers[Headers.CLIENT_REGION],
            networkType = headers[Headers.NETWORK_TYPE]
        )

        // Store on call attributes for downstream access
        call.attributes.put(MobileAttributes.Key, ctx)

        // Put into MDC for structured logging (null-safe)
        ctx.deviceId?.let { MDC.put("deviceId", it) }
        ctx.deviceModel?.let { MDC.put("deviceModel", it) }
        ctx.osName?.let { MDC.put("osName", it) }
        ctx.osVersion?.let { MDC.put("osVersion", it) }
        ctx.appVersion?.let { MDC.put("appVersion", it) }
        ctx.clientRegion?.let { MDC.put("clientRegion", it) }
        ctx.networkType?.let { MDC.put("networkType", it) }

        try {
            proceed()
        } finally {
            // Clean up MDC keys
            MDC.remove("deviceId")
            MDC.remove("deviceModel")
            MDC.remove("osName")
            MDC.remove("osVersion")
            MDC.remove("appVersion")
            MDC.remove("clientRegion")
            MDC.remove("networkType")
        }
    }
}

