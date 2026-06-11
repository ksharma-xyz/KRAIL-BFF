package app.krail.bff.routes

import app.krail.bff.proto.TrackRequest
import app.krail.bff.proto.TrackResponse
import app.krail.bff.track.FeedRegistry
import app.krail.bff.track.TrackJson
import app.krail.bff.track.TrackService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.koin.ktor.ext.inject

/**
 * Live trip tracking — POST /api/v1/track/snapshot.
 *
 * Request body: TrackRequest as protobuf (application/x-protobuf or
 * application/octet-stream) or JSON (application/json — used by the dev
 * dashboard). Response encoding follows the request's Accept header:
 * application/json → JSON, anything else → protobuf. Same payload either
 * way (see TrackJson).
 */
fun Application.configureTrackRoutes() {
    val service by inject<TrackService>()

    routing {
        post("/api/v1/track/snapshot") {
            val request = call.parseTrackRequest() ?: return@post
            val error = validate(request)
            if (error != null) {
                call.respondTrackError(HttpStatusCode.BadRequest, error.first, error.second)
                return@post
            }
            val response: TrackResponse = service.snapshot(request)
            if (call.request.headers["Accept"]?.contains("application/json") == true) {
                call.respondText(
                    text = TrackJson.renderResponse(response).toString(),
                    contentType = ContentType.Application.Json,
                )
            } else {
                call.respondBytes(
                    bytes = TrackResponse.ADAPTER.encode(response),
                    contentType = ContentType.Application.ProtoBuf,
                )
            }
        }
    }
}

private suspend fun ApplicationCall.parseTrackRequest(): TrackRequest? {
    return try {
        if (request.contentType().match(ContentType.Application.Json)) {
            TrackJson.parseRequest(Json.parseToJsonElement(receiveText()).jsonObject)
        } else {
            TrackRequest.ADAPTER.decode(receive<ByteArray>())
        }
    } catch (e: Exception) {
        respondTrackError(HttpStatusCode.BadRequest, "invalid_body", "request body is not a valid TrackRequest")
        null
    }
}

private val LEG_REF_REGEX = Regex("^[A-Za-z0-9._:-]{1,64}$")
private val TRIP_ID_REGEX = Regex("^[A-Za-z0-9._:-]{1,128}$")
private val STOP_ID_REGEX = Regex("^[A-Za-z0-9:]{1,40}$")
private val DATE_REGEX = Regex("^\\d{8}$")

private fun validate(request: TrackRequest): Pair<String, String>? {
    if (request.legs.isEmpty()) return "missing_legs" to "at least one leg is required"
    if (request.legs.size > 8) return "too_many_legs" to "at most 8 legs per request"
    request.legs.forEach { leg ->
        if (!leg.leg_ref.matches(LEG_REF_REGEX)) {
            return "invalid_leg_ref" to "leg_ref must match ${LEG_REF_REGEX.pattern}"
        }
        if (!leg.realtime_trip_id.trim().matches(TRIP_ID_REGEX)) {
            return "invalid_trip_id" to "realtime_trip_id must match ${TRIP_ID_REGEX.pattern}"
        }
        if (!FeedRegistry.isKnownProductClass(leg.product_class)) {
            return "invalid_product_class" to "product_class must be one of 1,2,4,5,7,9,11"
        }
        if (!leg.service_date.matches(DATE_REGEX)) {
            return "invalid_service_date" to "service_date must be YYYYMMDD"
        }
        if (leg.origin_stop_id.isNotEmpty() && !leg.origin_stop_id.matches(STOP_ID_REGEX)) {
            return "invalid_origin" to "origin_stop_id must match ${STOP_ID_REGEX.pattern}"
        }
        if (leg.destination_stop_id.isNotEmpty() && !leg.destination_stop_id.matches(STOP_ID_REGEX)) {
            return "invalid_destination" to "destination_stop_id must match ${STOP_ID_REGEX.pattern}"
        }
    }
    return null
}

private suspend fun ApplicationCall.respondTrackError(status: HttpStatusCode, code: String, message: String) {
    respondText(
        text = """{"error":{"code":"$code","message":"$message"}}""",
        contentType = ContentType.Application.Json,
        status = status,
    )
}
