package app.krail.bff.plugins

/**
 * Custom HTTP headers used by the BFF.
 * Centralizes header name constants to avoid magic strings and improve discoverability.
 */
object Headers {
    /**
     * Standard header for correlation/request IDs.
     * Follows the de-facto standard used by AWS ALB, Nginx, Kong, Heroku, etc.
     */
    const val REQUEST_ID = "X-Request-Id"

    // Add more custom headers here as needed:
    // const val API_KEY = "X-API-Key"
    // const val CLIENT_VERSION = "X-Client-Version"
}
