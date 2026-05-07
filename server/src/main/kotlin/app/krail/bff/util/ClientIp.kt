package app.krail.bff.util

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header

/**
 * Best-effort client IP extraction.
 *
 * Trusts Cloudflare's `CF-Connecting-IP` header first (origin firewall is locked to
 * Cloudflare's IP ranges, so this header is set by us). Falls back to the first hop
 * of `X-Forwarded-For`, then the socket peer.
 */
fun ApplicationCall.clientIp(): String =
    request.header("CF-Connecting-IP")
        ?: request.header("X-Forwarded-For")?.substringBefore(',')?.trim().takeIf { !it.isNullOrBlank() }
        ?: request.local.remoteHost
