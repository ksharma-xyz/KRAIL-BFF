package app.krail.bff.util

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header

/**
 * Best-effort client IP extraction.
 *
 * Proxy headers (`CF-Connecting-IP`, `X-Forwarded-For`) are only trusted when the
 * deployment actually sits behind Cloudflare — i.e. `CF_ORIGIN_TOKEN` is configured
 * (same signal that enables the origin-token gate), or `BFF_TRUST_PROXY_HEADERS=true`
 * is set explicitly. Otherwise anyone hitting the origin directly could rotate
 * `X-Forwarded-For` per request and sidestep the per-IP rate limiter entirely.
 */
private val trustProxyHeaders: Boolean by lazy {
    !System.getenv("CF_ORIGIN_TOKEN").isNullOrBlank() ||
        System.getenv("BFF_TRUST_PROXY_HEADERS")?.equals("true", ignoreCase = true) == true
}

fun ApplicationCall.clientIp(): String {
    if (trustProxyHeaders) {
        request.header("CF-Connecting-IP")?.takeIf { it.isNotBlank() }?.let { return it }
        request.header("X-Forwarded-For")?.substringBefore(',')?.trim()
            ?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return request.local.remoteHost
}
