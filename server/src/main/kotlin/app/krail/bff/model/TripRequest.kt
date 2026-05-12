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
 * Validation errors for trip requests. Each carries a stable code (for client log/UX
 * branching) and a generic message safe to send back.
 */
sealed class TripRequestError(val code: String, val message: String, val statusCode: Int = 400) {
    data object MissingOrigin : TripRequestError("missing_origin", "Missing 'origin' or 'name_origin' parameter")
    data object MissingDestination : TripRequestError("missing_destination", "Missing 'destination' or 'name_destination' parameter")
    data object InvalidStopId : TripRequestError("invalid_stop_id", "Stop IDs must be 1–32 alphanumeric characters")
    data object InvalidDate : TripRequestError("invalid_date", "date must be in YYYYMMDD format")
    data object InvalidTime : TripRequestError("invalid_time", "time must be in HHmm format")
    data object InvalidDepArr : TripRequestError("invalid_dep_arr", "depArr must be 'dep' or 'arr'")
    data object InvalidMode : TripRequestError("invalid_mode", "Mode IDs must be a subset of 1, 2, 4, 5, 7, 9, 11")

    fun toErrorResponse() = mapOf(
        "error" to "Bad Request",
        "code" to code,
        "message" to message,
        "statusCode" to statusCode,
    )
}

private val STOP_ID_REGEX = Regex("^[A-Za-z0-9]{1,32}$")
private val DATE_REGEX = Regex("^\\d{8}$")
private val TIME_REGEX = Regex("^\\d{4}$")
private val ALLOWED_DEP_ARR = setOf("dep", "arr")
private val ALLOWED_MODES = setOf(1, 2, 4, 5, 7, 9, 11)

/**
 * Validate parsed [TripRequest]. Returns null when valid, otherwise the first
 * encountered error (cheap-checks-first ordering).
 */
fun TripRequest.validate(): TripRequestError? = when {
    !origin.matches(STOP_ID_REGEX) -> TripRequestError.InvalidStopId
    !destination.matches(STOP_ID_REGEX) -> TripRequestError.InvalidStopId
    depArr !in ALLOWED_DEP_ARR -> TripRequestError.InvalidDepArr
    date != null && !date.matches(DATE_REGEX) -> TripRequestError.InvalidDate
    time != null && !time.matches(TIME_REGEX) -> TripRequestError.InvalidTime
    excludedModes.isNotEmpty() && !ALLOWED_MODES.containsAll(excludedModes) -> TripRequestError.InvalidMode
    else -> null
}

