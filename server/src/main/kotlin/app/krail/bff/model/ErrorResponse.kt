package app.krail.bff.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Standard error response format for all API endpoints.
 * Used when returning errors (never returned as protobuf).
 */
@Serializable
data class ErrorResponse(
    /**
     * Short error type (e.g., "Bad Request", "Internal Server Error")
     */
    @SerialName("error") val error: String,

    /**
     * Detailed error message
     */
    @SerialName("message") val message: String,

    /**
     * HTTP status code
     */
    @SerialName("statusCode") val statusCode: Int,

    /**
     * Optional field for additional error details
     */
    @SerialName("details") val details: Map<String, String>? = null,

    /**
     * Timestamp of the error
     */
    @SerialName("timestamp") val timestamp: String = java.time.Instant.now().toString()
)

