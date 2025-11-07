package app.krail.bff.model

import io.ktor.server.application.*

/**
 * Request parameters for trip planning endpoints.
 * Supports both legacy Android format and new simplified format.
 */
data class TripRequest(
    val origin: String,
    val destination: String,
    val depArr: String = "dep",
    val date: String? = null,
    val time: String? = null,
    val excludedModes: Set<Int> = emptySet()
)

/**
 * Parameter names constants for different API formats
 */
object TripRequestParams {
    // New API format (simplified)
    const val ORIGIN = "origin"
    const val DESTINATION = "destination"
    const val DEP_ARR = "depArr"
    const val DATE = "date"
    const val TIME = "time"
    const val EXCLUDED_MODES = "excludedModes"

    // Legacy Android format
    const val NAME_ORIGIN = "name_origin"
    const val NAME_DESTINATION = "name_destination"
    const val DEP_ARR_MACRO = "depArrMacro"
    const val ITD_DATE = "itdDate"
    const val ITD_TIME = "itdTime"
    const val EXCLUDED_MEANS = "excludedMeans"

    // Default values
    const val DEFAULT_DEP_ARR = "dep"
    const val CHECKBOX_PLACEHOLDER = "checkbox"
}

/**
 * Extension function to parse TripRequest from ApplicationCall query parameters.
 * Supports both new and legacy Android parameter formats.
 */
fun ApplicationCall.parseTripRequest(): TripRequest? {
    val params = request.queryParameters

    // Try new format first, then fall back to legacy format
    val origin = params[TripRequestParams.ORIGIN]
        ?: params[TripRequestParams.NAME_ORIGIN]
        ?: return null

    val destination = params[TripRequestParams.DESTINATION]
        ?: params[TripRequestParams.NAME_DESTINATION]
        ?: return null

    val depArr = params[TripRequestParams.DEP_ARR]
        ?: params[TripRequestParams.DEP_ARR_MACRO]
        ?: TripRequestParams.DEFAULT_DEP_ARR

    val date = params[TripRequestParams.DATE]
        ?: params[TripRequestParams.ITD_DATE]

    val time = params[TripRequestParams.TIME]
        ?: params[TripRequestParams.ITD_TIME]

    // Parse excluded modes from either format
    val excludedModesParam = params[TripRequestParams.EXCLUDED_MODES]
        ?: params[TripRequestParams.EXCLUDED_MEANS]

    val excludedModes = when {
        excludedModesParam == null -> emptySet()
        excludedModesParam == TripRequestParams.CHECKBOX_PLACEHOLDER -> emptySet()
        else -> excludedModesParam
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()
    }

    return TripRequest(
        origin = origin,
        destination = destination,
        depArr = depArr,
        date = date,
        time = time,
        excludedModes = excludedModes
    )
}

/**
 * Validation errors for trip requests
 */
sealed class TripRequestError(val message: String, val statusCode: Int) {
    object MissingOrigin : TripRequestError("Missing 'origin' or 'name_origin' parameter", 400)
    object MissingDestination : TripRequestError("Missing 'destination' or 'name_destination' parameter", 400)

    fun toErrorResponse() = mapOf(
        "error" to "Bad Request",
        "message" to message,
        "statusCode" to statusCode
    )
}

