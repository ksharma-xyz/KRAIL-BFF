package app.krail.bff.routes

import app.krail.bff.plugins.configureErrorHandling
import app.krail.bff.plugins.configureSerialization
import app.krail.bff.proto.JourneyList
import app.krail.bff.region.RegionRegistry
import app.krail.bff.region.RegionTripService
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

/**
 * Registry-driven plan-proto routes, exercised through the VIC and QLD specs
 * over the synthetic MiniNetworkFixture (`/api/v1/{code}/trip/plan-proto` is
 * one implementation for all seven regions).
 */
class RegionTripRoutesTest {

    // Clock pinned inside the fixture's calendar (repo invariant: injected
    // clock, never wall time) — Monday 2026-03-02 07:30 Melbourne.
    private val fixedNow = LocalDate.of(2026, 3, 2)
        .atTime(7, 30)
        .atZone(ZoneId.of("Australia/Melbourne"))
        .toInstant()

    private fun vicService() =
        RegionTripService(MiniNetworkFixture.buildModel(), RegionRegistry.vic, clock = { fixedNow })

    private fun qldService() =
        RegionTripService(MiniNetworkFixture.buildModel(), RegionRegistry.qld, clock = { fixedNow })

    @Test
    fun `plan-proto returns pareto journeys as protobuf`() = testApplication {
        val svc = vicService()
        application { regionTripRoutes(RegionRegistry.vic, svc) }

        val response =
            client.get("/api/v1/vic/trip/plan-proto?origin=VIC:A&destination=VIC:D&date=20260302&time=0755")
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
    }

    @Test
    fun `each region serves its own path with its own prefix and timezone`() = testApplication {
        application {
            regionTripRoutes(RegionRegistry.vic, vicService())
            regionTripRoutes(RegionRegistry.qld, qldService())
        }

        val vic =
            client.get("/api/v1/vic/trip/plan-proto?origin=VIC:A&destination=VIC:F&date=20260302&time=0755")
        assertEquals(HttpStatusCode.OK, vic.status)
        val vicJourney = JourneyList.ADAPTER.decode(vic.readRawBytes()).journeys.single()
        assertEquals(3, vicJourney.legs.size)
        assertTrue(vicJourney.legs[1].walking_leg != null)
        assertEquals("VIC:A", vicJourney.legs[0].transport_leg!!.stops.first().stop_id)

        val qld =
            client.get("/api/v1/qld/trip/plan-proto?origin=QLD:A&destination=QLD:D&date=20260302&time=0755")
        assertEquals(HttpStatusCode.OK, qld.status)
        val qldList = JourneyList.ADAPTER.decode(qld.readRawBytes())
        assertEquals("QLD:A", qldList.journeys[0].legs[0].transport_leg!!.stops.first().stop_id)
        // Instants derive from Brisbane local midnight — fixed +10:00, no DST.
        assertEquals("2026-03-01T22:00:00Z", qldList.journeys[1].origin_utc_date_time)

        // A region's path only accepts its own registration.
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/akl/trip/plan-proto?origin=A&destination=D").status,
        )
    }

    @Test
    fun `defaults date and time from the injected clock`() = testApplication {
        val svc = vicService()
        application { regionTripRoutes(RegionRegistry.vic, svc) }

        // No date/time: clock says Monday 07:30, so the 08:00 direct runs.
        val response = client.get("/api/v1/vic/trip/plan-proto?origin=A&destination=D")
        assertEquals(HttpStatusCode.OK, response.status)
        val list = JourneyList.ADAPTER.decode(response.readRawBytes())
        assertTrue(list.journeys.isNotEmpty())
    }

    @Test
    fun `rejects malformed stop ids`() = testApplication {
        val svc = vicService()
        application { regionTripRoutes(RegionRegistry.vic, svc) }

        val response = client.get("/api/v1/vic/trip/plan-proto?origin=A%20OR%201=1&destination=D")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "invalid_stop_id")
    }

    @Test
    fun `rejects malformed date and time`() = testApplication {
        val svc = vicService()
        application { regionTripRoutes(RegionRegistry.vic, svc) }

        val badDate = client.get("/api/v1/vic/trip/plan-proto?origin=A&destination=D&date=2026-03-02")
        assertEquals(HttpStatusCode.BadRequest, badDate.status)
        assertContains(badDate.bodyAsText(), "invalid_date")

        // Matches \d{8} but is not a calendar date — must be 400, not a 500.
        val impossibleDate = client.get("/api/v1/vic/trip/plan-proto?origin=A&destination=D&date=20260230")
        assertEquals(HttpStatusCode.BadRequest, impossibleDate.status)
        assertContains(impossibleDate.bodyAsText(), "invalid_date")

        val badTime = client.get("/api/v1/vic/trip/plan-proto?origin=A&destination=D&time=2560")
        assertEquals(HttpStatusCode.BadRequest, badTime.status)
        assertContains(badTime.bodyAsText(), "invalid_time")
    }

    @Test
    fun `unknown stop id returns 404 with the standard envelope`() = testApplication {
        val svc = vicService()
        // Mirror production: StatusPages owns 404 bodies (ErrorEnvelope).
        application {
            configureSerialization()
            configureErrorHandling()
            regionTripRoutes(RegionRegistry.vic, svc)
        }

        val response = client.get("/api/v1/vic/trip/plan-proto?origin=VIC:ZZZZ&destination=VIC:D")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertContains(response.bodyAsText(), "not_found")
    }

    @Test
    fun `registry is well-formed`() {
        val codes = RegionRegistry.all.map { it.code }
        assertEquals(codes.toSet().size, codes.size, "duplicate region codes")
        val prefixes = RegionRegistry.all.map { it.idPrefix }
        assertEquals(prefixes.toSet().size, prefixes.size, "duplicate id prefixes")
        for (spec in RegionRegistry.all) {
            assertTrue(spec.code.matches(Regex("^[a-z]{2,8}$")), "code '${spec.code}' must be lowercase (path segment)")
            assertEquals(spec.code.uppercase(), spec.idPrefix, "prefix convention broken for ${spec.code}")
            assertEquals("${spec.code.uppercase()}_ROUTER_SNAPSHOT", spec.snapshotEnvVar)
            assertEquals("${spec.code}.routerSnapshot", spec.snapshotConfigKey)
        }
        // The five world regions + qld are single-feed; vic is not.
        assertEquals(
            listOf("qld", "akl", "wlg", "bos", "ber", "prg"),
            RegionRegistry.singleFeed.map { it.code },
        )
    }
}
