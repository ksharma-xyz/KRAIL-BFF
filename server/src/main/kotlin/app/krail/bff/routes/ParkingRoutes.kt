package app.krail.bff.routes

import app.krail.bff.client.nsw.NswClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

// Compile-once: validates path-segment facility IDs.
private val FACILITY_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,40}$")

private const val INVALID_FACILITY_ID_BODY =
    "{\"error\":{\"code\":\"invalid_facility_id\"," +
    "\"message\":\"facilityId must be alphanumeric, 1-40 chars\"}}"

/**
 * Park & Ride endpoints. Pass-through of NSW `/v1/carpark` for v1.
 *
 * - GET /v1/parking/facilities — list of facilities (NSW returns the full list when no facility= param)
 * - GET /v1/parking/facilities/{facilityId}/availability — single facility's occupancy
 */
fun Application.configureParkingRoutes() {
    val nsw by inject<NswClient>()

    routing {
        route("/v1/parking/facilities") {
            get {
                val body = nsw.getCarparkRaw(facilityId = null)
                call.respondText(text = body, contentType = ContentType.Application.Json)
            }

            get("/{facilityId}/availability") {
                val facilityId = call.parameters["facilityId"]
                if (facilityId.isNullOrBlank() || !facilityId.matches(FACILITY_ID_REGEX)) {
                    call.respondText(
                        text = INVALID_FACILITY_ID_BODY,
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.BadRequest,
                    )
                    return@get
                }
                val body = nsw.getCarparkRaw(facilityId = facilityId)
                call.respondText(text = body, contentType = ContentType.Application.Json)
            }
        }
    }
}
