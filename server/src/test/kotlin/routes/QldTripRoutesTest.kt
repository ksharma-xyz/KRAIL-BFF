package app.krail.bff.routes

import app.krail.bff.plugins.configureErrorHandling
import app.krail.bff.plugins.configureSerialization
import app.krail.bff.proto.JourneyList
import app.krail.bff.qld.QldTripService
import app.krail.bff.router.MiniNetworkFixture
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QldTripRoutesTest {

    // Clock pinned inside the fixture's calendar (repo invariant: injected
    // clock, never wall time) — Monday 2026-03-02 07:30 Brisbane.
    private val fixedNow = LocalDate.of(2026, 3, 2)
        .atTime(7, 30)
        .atZone(ZoneId.of("Australia/Brisbane"))
        .toInstant()

    private fun service() = QldTripService(MiniNetworkFixture.buildModel(), clock = { fixedNow })

    @Test
    fun `plan-proto returns pareto journeys as protobuf`() = testApplication {
        val svc = service()
        application { qldTripRoutes(svc) }

        val response =
            client.get("/api/v1/qld/trip/plan-proto?origin=QLD:A&destination=QLD:D&date=20260302&time=0755")
        assertEquals(HttpStatusCode.OK, response.status)
        val list = JourneyList.ADAPTER.decode(response.readRawBytes())
        assertEquals(2, list.journeys.size)
        // Fastest arrival first: express + connector (8:30), then direct (8:40).
        val withTransfer = list.journeys[0]
        assertEquals("8:30am", withTransfer.destination_time)
        assertEquals(2, withTransfer.legs.size)
        val direct = list.journeys[1]
        assertEquals("8:40am", direct.destination_time)
        assertEquals("in 30 mins", direct.time_text)
        // Instants derive from Brisbane local midnight — fixed +10:00, no DST.
        assertEquals("2026-03-01T22:00:00Z", direct.origin_utc_date_time)
    }

    @Test
    fun `walk transfer surfaces as walking leg with qld-prefixed stop ids`() = testApplication {
        val svc = service()
        application { qldTripRoutes(svc) }

        val response =
            client.get("/api/v1/qld/trip/plan-proto?origin=QLD:A&destination=QLD:F&date=20260302&time=0755")
        assertEquals(HttpStatusCode.OK, response.status)
        val journey = JourneyList.ADAPTER.decode(response.readRawBytes()).journeys.single()
        assertEquals(3, journey.legs.size)
        assertTrue(journey.legs[1].walking_leg != null)
        val firstStop = journey.legs[0].transport_leg!!.stops.first()
        assertEquals("QLD:A", firstStop.stop_id)
    }

    @Test
    fun `defaults date and time from the injected clock`() = testApplication {
        val svc = service()
        application { qldTripRoutes(svc) }

        // No date/time: clock says Monday 07:30, so the 08:00 direct runs.
        val response = client.get("/api/v1/qld/trip/plan-proto?origin=A&destination=D")
        assertEquals(HttpStatusCode.OK, response.status)
        val list = JourneyList.ADAPTER.decode(response.readRawBytes())
        assertTrue(list.journeys.isNotEmpty())
    }

    @Test
    fun `rejects malformed stop ids`() = testApplication {
        val svc = service()
        application { qldTripRoutes(svc) }

        val response = client.get("/api/v1/qld/trip/plan-proto?origin=A%20OR%201=1&destination=D")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "invalid_stop_id")
    }

    @Test
    fun `rejects malformed date and time`() = testApplication {
        val svc = service()
        application { qldTripRoutes(svc) }

        val badDate = client.get("/api/v1/qld/trip/plan-proto?origin=A&destination=D&date=2026-03-02")
        assertEquals(HttpStatusCode.BadRequest, badDate.status)
        assertContains(badDate.bodyAsText(), "invalid_date")

        // Matches \d{8} but is not a calendar date — must be 400, not a 500.
        val impossibleDate = client.get("/api/v1/qld/trip/plan-proto?origin=A&destination=D&date=20260230")
        assertEquals(HttpStatusCode.BadRequest, impossibleDate.status)
        assertContains(impossibleDate.bodyAsText(), "invalid_date")

        val badTime = client.get("/api/v1/qld/trip/plan-proto?origin=A&destination=D&time=2560")
        assertEquals(HttpStatusCode.BadRequest, badTime.status)
        assertContains(badTime.bodyAsText(), "invalid_time")
    }

    @Test
    fun `unknown stop id returns 404 with the standard envelope`() = testApplication {
        val svc = service()
        // Mirror production: StatusPages owns 404 bodies (ErrorEnvelope).
        application {
            configureSerialization()
            configureErrorHandling()
            qldTripRoutes(svc)
        }

        val response = client.get("/api/v1/qld/trip/plan-proto?origin=QLD:ZZZZ&destination=QLD:D")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertContains(response.bodyAsText(), "not_found")
    }
}
