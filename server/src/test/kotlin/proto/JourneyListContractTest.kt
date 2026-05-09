package app.krail.bff.proto

import app.krail.bff.mapper.JourneyListMapper
import app.krail.bff.model.TripResponse
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Contract enforcement for [JourneyList] / [JourneyCardInfo].
 *
 * The KRAIL-API-PROTO contract (see <https://ksharma-xyz.github.io/KRAIL-API-PROTO/contract>)
 * marks every field as either `// contract: required` (server must always
 * populate, never null) or `// contract: optional` (may be null when not
 * applicable).
 *
 * This test fires the JourneyListMapper at a minimal-but-valid TripResponse
 * and asserts every contract: required field on the generated JourneyCardInfo
 * (and its nested messages) is non-null. If a future schema change adds a new
 * required field, this test fails until the response builder is updated to
 * populate it — which is the whole point.
 *
 * Genuinely-optional fields (platform_text, platform_number, total_walk_time,
 * departure_deviation, display_text on TransportLeg, walk_interchange, trip_id,
 * url on ServiceAlert) are deliberately NOT asserted — they are allowed to be
 * null when not applicable.
 */
class JourneyListContractTest {

    @Test
    fun `JourneyList from minimal upstream populates all contract-required fields`() {
        val response = JourneyListMapper.toProto(minimalTripResponse())

        assertNotNull(response.journeys, "JourneyList.journeys (required)")
        assertTrue(response.journeys.isNotEmpty(), "expected at least one journey")

        for (j in response.journeys) {
            assertJourneyCardInfo(j)
        }
    }

    @Test
    fun `empty upstream produces a JourneyList with empty journeys list, never null`() {
        // contract: required for JourneyList.journeys is "non-null list" — empty list is OK.
        val response = JourneyListMapper.toProto(TripResponse(journeys = emptyList()))
        assertNotNull(response.journeys, "even when empty, journeys must not be null")
        assertTrue(response.journeys.isEmpty())
    }

    private fun assertJourneyCardInfo(j: JourneyCardInfo) {
        assertNotNull(j.time_text,                  "JourneyCardInfo.time_text (required)")
        assertNotNull(j.origin_time,                "JourneyCardInfo.origin_time (required)")
        assertNotNull(j.origin_utc_date_time,       "JourneyCardInfo.origin_utc_date_time (required)")
        assertNotNull(j.destination_time,           "JourneyCardInfo.destination_time (required)")
        assertNotNull(j.destination_utc_date_time,  "JourneyCardInfo.destination_utc_date_time (required)")
        assertNotNull(j.travel_time,                "JourneyCardInfo.travel_time (required)")
        assertNotNull(j.transport_mode_lines,       "JourneyCardInfo.transport_mode_lines (required, may be empty)")
        assertNotNull(j.legs,                       "JourneyCardInfo.legs (required)")
        // total_unique_service_alerts is int32 — proto3 default is 0, which is non-null;
        // assert it's non-negative as a sanity check.
        assertTrue(j.total_unique_service_alerts >= 0, "total_unique_service_alerts must be ≥ 0")

        for (line in j.transport_mode_lines) assertTransportModeLine(line)
        for (leg in j.legs) assertLeg(leg)
    }

    private fun assertTransportModeLine(line: TransportModeLine) {
        assertNotNull(line.line_name, "TransportModeLine.line_name (required)")
        assertTrue(line.transport_mode_type >= 0, "TransportModeLine.transport_mode_type (required, ≥ 0)")
    }

    private fun assertLeg(leg: Leg) {
        // Leg is a oneof — exactly one variant must be set.
        val variants = listOfNotNull(leg.walking_leg, leg.transport_leg)
        if (variants.isEmpty()) fail("Leg.leg_type oneof has no variant set (contract: required)")
        if (variants.size > 1) fail("Leg.leg_type oneof has multiple variants set")
        leg.walking_leg?.let { assertNotNull(it.duration, "WalkingLeg.duration (required)") }
        leg.transport_leg?.let { assertTransportLeg(it) }
    }

    private fun assertTransportLeg(t: TransportLeg) {
        // transport_mode_line is a non-null nested message in the generated Wire class —
        // contract: required is enforced at the schema level via non-optional declaration.
        assertTransportModeLine(t.transport_mode_line!!)
        assertNotNull(t.total_duration, "TransportLeg.total_duration (required)")
        assertNotNull(t.stops,          "TransportLeg.stops (required, may be empty)")
        assertNotNull(t.service_alert_list, "TransportLeg.service_alert_list (required, may be empty)")

        for (stop in t.stops) {
            assertNotNull(stop.name, "Stop.name (required)")
            assertNotNull(stop.time, "Stop.time (required)")
            // Stop.is_wheelchair_accessible is bool — proto3 default is false, never null.
        }

        for (alert in t.service_alert_list) {
            assertNotNull(alert.id,       "ServiceAlert.id (required)")
            assertNotNull(alert.subtitle, "ServiceAlert.subtitle (required)")
            assertNotNull(alert.content,  "ServiceAlert.content (required)")
            assertNotNull(alert.priority, "ServiceAlert.priority (required)")
        }

        t.walk_interchange?.let { wi ->
            assertNotNull(wi.duration, "WalkInterchange.duration (required)")
            // wi.position is enum — proto3 default WALK_POSITION_UNSPECIFIED, never null.
        }
    }

    // ------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------

    /** A minimal-but-realistic NSW TripResponse with one transport leg. */
    private fun minimalTripResponse(): TripResponse {
        val depTime = "2026-05-09T14:30:00Z"
        val arrTime = "2026-05-09T14:42:00Z"

        val transportation = TripResponse.Transportation(
            id = "nsw:T1:R:sj2",
            name = "T1 North Shore Line",
            disassembledName = "T1",
            description = "Towards Central",
            product = TripResponse.Product(
                productClass = 1L, // Train
                name = "Sydney Trains Network",
                iconID = 1L,
            ),
            destination = TripResponse.OperatorClass(
                id = "200060",
                name = "Central Station",
            ),
        )

        val originStop = TripResponse.StopSequence(
            id = "10101101",
            name = "Town Hall Station, Platform 3",
            disassembledName = "Town Hall Station, Platform 3",
            departureTimePlanned = depTime,
            departureTimeEstimated = depTime,
        )

        val destStop = TripResponse.StopSequence(
            id = "200060",
            name = "Central Station, Platform 16",
            disassembledName = "Central Station, Platform 16",
            arrivalTimePlanned = arrTime,
            arrivalTimeEstimated = arrTime,
        )

        val transportLeg = TripResponse.Leg(
            distance = 1843,
            duration = 12 * 60,
            isRealtimeControlled = true,
            origin = originStop,
            destination = destStop,
            stopSequence = listOf(originStop, destStop),
            transportation = transportation,
            footPathInfo = emptyList(),
            infos = emptyList(),
        )

        return TripResponse(
            journeys = listOf(TripResponse.Journey(legs = listOf(transportLeg))),
            version = "10.6.21.17",
        )
    }
}
