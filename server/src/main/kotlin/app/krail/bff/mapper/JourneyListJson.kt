package app.krail.bff.mapper

import app.krail.bff.proto.Coord
import app.krail.bff.proto.JourneyCardInfo
import app.krail.bff.proto.JourneyList
import app.krail.bff.proto.Leg
import app.krail.bff.proto.Stop
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * JSON rendition of the JourneyList proto for browser consumers (the Router
 * Lab dev dashboard). Field names match the proto exactly so JSON and proto
 * are two views of one payload — same precedent as [app.krail.bff.track.TrackJson].
 * Shared by the NSW and VIC trip endpoints; both produce JourneyList.
 */
object JourneyListJson {

    fun render(list: JourneyList): JsonObject = buildJsonObject {
        put("journeys", buildJsonArray { list.journeys.forEach { add(renderJourney(it)) } })
    }

    private fun renderJourney(j: JourneyCardInfo): JsonObject = buildJsonObject {
        put("time_text", j.time_text)
        j.platform_text?.let { put("platform_text", it) }
        j.platform_number?.let { put("platform_number", it) }
        put("origin_time", j.origin_time)
        put("origin_utc_date_time", j.origin_utc_date_time)
        put("destination_time", j.destination_time)
        put("destination_utc_date_time", j.destination_utc_date_time)
        put("travel_time", j.travel_time)
        j.total_walk_time?.let { put("total_walk_time", it) }
        put("transport_mode_lines", buildJsonArray {
            j.transport_mode_lines.forEach { line ->
                add(buildJsonObject {
                    put("line_name", line.line_name)
                    put("transport_mode_type", line.transport_mode_type)
                })
            }
        })
        put("legs", buildJsonArray { j.legs.forEach { add(renderLeg(it)) } })
        put("total_unique_service_alerts", j.total_unique_service_alerts)
        j.departure_deviation?.let { d ->
            put("departure_deviation", buildJsonObject {
                d.on_time?.let { put("on_time", it) }
                d.late?.let { put("late", it) }
                d.early?.let { put("early", it) }
            })
        }
    }

    private fun renderLeg(leg: Leg): JsonObject = buildJsonObject {
        leg.walking_leg?.let { w ->
            put("walking_leg", buildJsonObject {
                put("duration", w.duration)
                put("coords", renderCoords(w.coords))
            })
        }
        leg.transport_leg?.let { t ->
            put("transport_leg", buildJsonObject {
                t.transport_mode_line?.let { line ->
                    put("transport_mode_line", buildJsonObject {
                        put("line_name", line.line_name)
                        put("transport_mode_type", line.transport_mode_type)
                    })
                }
                t.display_text?.let { put("display_text", it) }
                put("total_duration", t.total_duration)
                put("stops", buildJsonArray { t.stops.forEach { add(renderStop(it)) } })
                t.walk_interchange?.let { w ->
                    put("walk_interchange", buildJsonObject {
                        put("duration", w.duration)
                        put("position", w.position.name)
                        put("coords", renderCoords(w.coords))
                    })
                }
                put("service_alert_list", buildJsonArray {
                    t.service_alert_list.forEach { a ->
                        add(buildJsonObject {
                            put("id", a.id)
                            put("subtitle", a.subtitle)
                            put("content", a.content)
                            put("priority", a.priority)
                            a.url?.let { put("url", it) }
                        })
                    }
                })
                t.trip_id?.let { put("trip_id", it) }
                put("coords", renderCoords(t.coords))
                t.transportation_id?.let { put("transportation_id", it) }
                t.realtime_trip_id?.let { put("realtime_trip_id", it) }
            })
        }
    }

    private fun renderStop(s: Stop): JsonObject = buildJsonObject {
        put("name", s.name)
        put("time", s.time)
        put("is_wheelchair_accessible", s.is_wheelchair_accessible)
        s.coord?.let { c ->
            put("coord", buildJsonObject {
                put("lat", c.lat)
                put("lon", c.lon)
            })
        }
        s.stop_id?.let { put("stop_id", it) }
        s.utc_time?.let { put("utc_time", it) }
    }

    private fun renderCoords(coords: List<Coord>) = buildJsonArray {
        coords.forEach { c ->
            add(buildJsonObject {
                put("lat", c.lat)
                put("lon", c.lon)
            })
        }
    }
}
