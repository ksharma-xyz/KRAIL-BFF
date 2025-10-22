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

    // Mobile analytics headers (sent by the app)
    const val DEVICE_ID = "X-Device-Id"
    const val DEVICE_MODEL = "X-Device-Model"
    const val OS_NAME = "X-OS-Name"
    const val OS_VERSION = "X-OS-Version"
    const val APP_VERSION = "X-App-Version"
    const val CLIENT_REGION = "X-Client-Region" // optional
    const val NETWORK_TYPE = "X-Network-Type"   // optional
}
