package app.krail.bff.track

import app.krail.bff.proto.LegTracking
import app.krail.bff.proto.TrackRequest
import app.krail.bff.proto.TrackResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * JSON rendition of the track contract for browser consumers (the dev
 * dashboard). Field names match track.proto exactly so the JSON and proto
 * encodings are two views of one payload — the dashboard tests the real
 * code path, not a parallel one.
 */
object TrackJson {

    fun parseRequest(json: JsonObject): TrackRequest {
        val legs = (json["legs"] as? JsonArray ?: JsonArray(emptyList())).map { el ->
            val o = el.jsonObject
            TrackRequest.TrackLeg(
                leg_ref = o.str("leg_ref"),
                realtime_trip_id = o.str("realtime_trip_id"),
                transportation_id = o.str("transportation_id"),
                product_class = o["product_class"]?.jsonPrimitive?.intOrNull ?: 0,
                origin_stop_id = o.str("origin_stop_id"),
                destination_stop_id = o.str("destination_stop_id"),
                service_date = o.str("service_date"),
                planned_departure_utc = o.str("planned_departure_utc"),
            )
        }
        return TrackRequest(
            legs = legs,
            include_geometry = json["include_geometry"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    fun renderResponse(response: TrackResponse): JsonObject = buildJsonObject {
        put("fetched_at_epoch_sec", response.fetched_at_epoch_sec)
        put("suggested_poll_seconds", response.suggested_poll_seconds)
        put("legs", buildJsonArray { response.legs.forEach { add(renderLeg(it)) } })
    }

    private fun renderLeg(leg: LegTracking): JsonObject = buildJsonObject {
        put("leg_ref", leg.leg_ref)
        put("status", leg.status.name)
        leg.vehicle?.let { v ->
            put("vehicle", buildJsonObject {
                put("latitude", v.latitude)
                put("longitude", v.longitude)
                if (v.has_bearing) put("bearing_degrees", v.bearing_degrees)
                if (v.has_speed) put("speed_mps", v.speed_mps)
                put("measured_at_epoch_sec", v.measured_at_epoch_sec)
                put("stop_relation", v.stop_relation.name)
                if (v.at_or_next_stop_id.isNotEmpty()) put("at_or_next_stop_id", v.at_or_next_stop_id)
            })
        }
        leg.fleet?.let { f ->
            put("fleet", buildJsonObject {
                put("display_name", f.display_name)
                if (f.set_code.isNotEmpty()) put("set_code", f.set_code)
                if (f.car_count > 0) put("car_count", f.car_count)
                put("source", f.source.name)
            })
        }
        leg.occupancy?.let { o ->
            put("occupancy", buildJsonObject {
                put("overall", o.overall.name)
                put("cars", buildJsonArray {
                    o.cars.forEach { car ->
                        add(buildJsonObject {
                            put("sequence", car.sequence)
                            if (car.label.isNotEmpty()) put("label", car.label)
                            put("level", car.level.name)
                            if (car.quiet_carriage) put("quiet_carriage", true)
                        })
                    }
                })
            })
        }
        put("stops", buildJsonArray {
            leg.stops.forEach { s ->
                add(buildJsonObject {
                    put("stop_id", s.stop_id)
                    if (s.stop_name.isNotEmpty()) put("stop_name", s.stop_name)
                    if (s.planned_epoch_sec != 0L) put("planned_epoch_sec", s.planned_epoch_sec)
                    if (s.estimated_epoch_sec != 0L) put("estimated_epoch_sec", s.estimated_epoch_sec)
                    put("state", s.state.name)
                })
            }
        })
        if (leg.has_delay) put("delay_seconds", leg.delay_seconds)
        leg.geometry?.let { g ->
            put("geometry", buildJsonObject {
                put("encoded_polyline", g.encoded_polyline)
                put("source", g.source.name)
            })
        }
    }

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.takeIf { it.isString || it.content.isNotEmpty() }?.content ?: ""
}
