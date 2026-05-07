package app.krail.bff.client.nsw

/**
 * Typed exceptions thrown by [NswClient]. The error-handling plugin maps these
 * to specific HTTP responses without leaking upstream details.
 */
sealed class NswException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** NSW upstream returned a non-2xx status. The body is captured for server-side logs only. */
class NswUpstreamException(
    val statusCode: Int,
    message: String,
    val responseBody: String,
) : NswException(message)

/** Daily NSW upstream call budget exceeded; clients should back off. */
class NswBudgetExceededException(message: String = "Daily NSW call budget exceeded") : NswException(message)
