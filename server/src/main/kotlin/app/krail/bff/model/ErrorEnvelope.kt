package app.krail.bff.model

import kotlinx.serialization.Serializable

/**
 * Details about an error, including a machine-readable code and human-readable message.
 */
@Serializable
data class ErrorDetails(
    val code: String,
    val message: String,
    val details: Map<String, String>? = null
)

/**
 * Standard error envelope for all error responses.
 * Always includes correlationId for request tracing.
 */
@Serializable
data class ErrorEnvelope(
    val success: Boolean = false,
    val error: ErrorDetails,
    val correlationId: String? = null
)
