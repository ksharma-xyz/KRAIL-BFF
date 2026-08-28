package app.krail.bff.routes

import app.krail.bff.client.nsw.NswClient
import app.krail.bff.model.TripResponse
import app.krail.bff.proto.JourneyCardInfo
import app.krail.bff.proto.JourneyList
import app.krail.bff.region.RegionRegistry
import app.krail.bff.region.RegionTripService
import app.krail.bff.router.MiniNetworkFixture
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouterLabRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val fixedNow = LocalDate.of(2026, 3, 2)
        .atTime(7, 30)
        .atZone(ZoneId.of("Australia/Melbourne"))
        .toInstant()

    private fun vicService() =
        RegionTripService(MiniNetworkFixture.buildModel(), RegionRegistry.vic, clock = { fixedNow })

    private fun qldService() =
        RegionTripService(MiniNetworkFixture.buildModel(), RegionRegistry.qld, clock = { fixedNow })

    @AfterTest
    fun tearDown() {
        runCatching { GlobalContext.stopKoin() }
    }

    // ---- Accept-header JSON switch (dev-gated) -----------------------------

    @Test
    fun `vic plan-proto returns json mirror when accept is json and gate on`() = testApplication {
        environment { config = MapApplicationConfig("bff.devPassthrough" to "true") }
        val svc = vicService()
        application { regionTripRoutes(RegionRegistry.vic, svc) }

        val response = client.get(
            "/api/v1/vic/trip/plan-proto?origin=VIC:A&destination=VIC:D&date=20260302&time=0755"
        ) { header("Accept", "application/json") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.contentType()?.match(ContentType.Application.Json) == true)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val journeys = body["journeys"]!!.jsonArray
        assertEquals(2, journeys.size)
        val first = journeys[0].jsonObject
        assertEquals("8:30am", first["destination_time"]!!.jsonPrimitive.content)
        // Legs carry stop ids — the debug value of the lab.
        val leg = first["legs"]!!.jsonArray[0].jsonObject["transport_leg"]!!.jsonObject
        assertEquals("VIC:A", leg["stops"]!!.jsonArray[0].jsonObject["stop_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `vic plan-proto still defaults to protobuf`() = testApplication {
        environment { config = MapApplicationConfig("bff.devPassthrough" to "true") }
        val svc = vicService()
        application { regionTripRoutes(RegionRegistry.vic, svc) }

        val response =
            client.get("/api/v1/vic/trip/plan-proto?origin=VIC:A&destination=VIC:D&date=20260302&time=0755")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.contentType()?.match(ContentType.Application.ProtoBuf) == true)
        assertEquals(2, JourneyList.ADAPTER.decode(response.readRawBytes()).journeys.size)
    }

    @Test
    fun `accept json without dev gate still returns protobuf`() = testApplication {
        // Production safety: client-side ContentNegotiation may append
        // application/json to Accept — the encoding must not change when the
        // dev gate is off (all production configs).
        val svc = vicService()
        application { regionTripRoutes(RegionRegistry.vic, svc) }

        val response = client.get(
            "/api/v1/vic/trip/plan-proto?origin=VIC:A&destination=VIC:D&date=20260302&time=0755"
        ) { header("Accept", "application/json") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.contentType()?.match(ContentType.Application.ProtoBuf) == true)
        assertEquals(2, JourneyList.ADAPTER.decode(response.readRawBytes()).journeys.size)
    }

    @Test
    fun `qld plan-proto returns json mirror when accept is json and gate on`() = testApplication {
        environment { config = MapApplicationConfig("bff.devPassthrough" to "true") }
        val svc = qldService()
        application { regionTripRoutes(RegionRegistry.qld, svc) }

        val response = client.get(
            "/api/v1/qld/trip/plan-proto?origin=QLD:A&destination=QLD:D&date=20260302&time=0755"
        ) { header("Accept", "application/json") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.contentType()?.match(ContentType.Application.Json) == true)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val journeys = body["journeys"]!!.jsonArray
        assertEquals(2, journeys.size)
        val leg = journeys[0].jsonObject["legs"]!!.jsonArray[0].jsonObject["transport_leg"]!!.jsonObject
        assertEquals("QLD:A", leg["stops"]!!.jsonArray[0].jsonObject["stop_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `qld accept json without dev gate still returns protobuf`() = testApplication {
        // Same production safety as VIC: the encoding must not change when the
        // dev gate is off (all production configs), whatever Accept says.
        val svc = qldService()
        application { regionTripRoutes(RegionRegistry.qld, svc) }

        val response = client.get(
            "/api/v1/qld/trip/plan-proto?origin=QLD:A&destination=QLD:D&date=20260302&time=0755"
        ) { header("Accept", "application/json") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.contentType()?.match(ContentType.Application.ProtoBuf) == true)
        assertEquals(2, JourneyList.ADAPTER.decode(response.readRawBytes()).journeys.size)
    }

    @Test
    fun `nsw plan-proto returns json mirror when accept is json and gate on`() = testApplication {
        environment { config = MapApplicationConfig("bff.devPassthrough" to "true") }
        application {
            install(Koin) { modules(module { single<NswClient> { StubNswClient() } }) }
            configureTripRoutes()
        }

        val response = client.get("/api/v1/trip/plan-proto?origin=200060&destination=10101100") {
            header("Accept", "application/json")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.contentType()?.match(ContentType.Application.Json) == true)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val journey = body["journeys"]!!.jsonArray.single().jsonObject
        assertEquals("7:41am", journey["origin_time"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nsw plan-proto still defaults to protobuf`() = testApplication {
        application {
            install(Koin) { modules(module { single<NswClient> { StubNswClient() } }) }
            configureTripRoutes()
        }

        val response = client.get("/api/v1/trip/plan-proto?origin=200060&destination=10101100")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.contentType()?.match(ContentType.Application.ProtoBuf) == true)
        assertEquals(1, JourneyList.ADAPTER.decode(response.readRawBytes()).journeys.size)
    }

    // ---- Stop search -------------------------------------------------------

    @Test
    fun `stop search returns matching stops with vic ids`() = testApplication {
        val svc = vicService()
        application { routerLabRoutes(mapOf("vic" to svc)) }

        val response = client.get("/internal/vic/stops/search?q=stop d")
        assertEquals(HttpStatusCode.OK, response.status)
        val hits = json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertTrue(hits.isNotEmpty())
        val ids = hits.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertContains(ids, "VIC:D")
        assertContains(ids, "VIC:D2")
        val d = hits.first { it.jsonObject["id"]!!.jsonPrimitive.content == "VIC:D" }.jsonObject
        assertEquals("Stop D", d["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `stop search rejects injection-shaped and missing queries`() = testApplication {
        val svc = vicService()
        application { routerLabRoutes(mapOf("vic" to svc)) }

        val injection = client.get("/internal/vic/stops/search?q=%3Cscript%3Ealert(1)%3C/script%3E")
        assertEquals(HttpStatusCode.BadRequest, injection.status)
        assertContains(injection.bodyAsText(), "invalid_query")

        val missing = client.get("/internal/vic/stops/search")
        assertEquals(HttpStatusCode.BadRequest, missing.status)

        val tooLong = client.get("/internal/vic/stops/search?q=" + "a".repeat(65))
        assertEquals(HttpStatusCode.BadRequest, tooLong.status)
    }

    @Test
    fun `stop search absent when vic model not loaded`() = testApplication {
        application { routerLabRoutes(emptyMap()) }

        assertEquals(HttpStatusCode.NotFound, client.get("/internal/vic/stops/search?q=stop").status)
        // The lab page itself still serves (NSW-only debugging).
        assertEquals(HttpStatusCode.OK, client.get("/internal/router-lab").status)
    }

    @Test
    fun `vic and qld stop search serve side by side with both models loaded`() = testApplication {
        application { routerLabRoutes(mapOf("vic" to vicService(), "qld" to qldService())) }

        val vic = client.get("/internal/vic/stops/search?q=stop d")
        assertEquals(HttpStatusCode.OK, vic.status)
        val vicIds = json.parseToJsonElement(vic.bodyAsText()).jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertContains(vicIds, "VIC:D")

        val qld = client.get("/internal/qld/stops/search?q=stop d")
        assertEquals(HttpStatusCode.OK, qld.status)
        val qldIds = json.parseToJsonElement(qld.bodyAsText()).jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertContains(qldIds, "QLD:D")

        // QLD search is allowlist-validated like VIC's.
        val injection = client.get("/internal/qld/stops/search?q=%3Cscript%3E")
        assertEquals(HttpStatusCode.BadRequest, injection.status)
        assertContains(injection.bodyAsText(), "invalid_query")
    }

    @Test
    fun `qld stop search absent when qld model not loaded`() = testApplication {
        application { routerLabRoutes(mapOf("vic" to vicService())) }

        assertEquals(HttpStatusCode.OK, client.get("/internal/vic/stops/search?q=stop").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/internal/qld/stops/search?q=stop").status)
    }

    @Test
    fun `regions endpoint lists every registry region with loaded flags`() = testApplication {
        application { routerLabRoutes(mapOf("vic" to vicService())) }

        val response = client.get("/internal/router-lab/regions")
        assertEquals(HttpStatusCode.OK, response.status)
        val regions = json.parseToJsonElement(response.bodyAsText()).jsonArray
            .associate { r ->
                r.jsonObject["code"]!!.jsonPrimitive.content to r.jsonObject
            }
        // Every registry region appears, loaded or not — the lab greys out
        // the unloaded ones.
        assertEquals(RegionRegistry.all.map { it.code }.toSet(), regions.keys)
        assertEquals(true, regions["vic"]!!["loaded"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, regions["akl"]!!["loaded"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("AKL", regions["akl"]!!["prefix"]!!.jsonPrimitive.content)
        assertEquals("Pacific/Auckland", regions["akl"]!!["tz"]!!.jsonPrimitive.content)
    }

    @Test
    fun `stop search accepts unicode letters in queries`() = testApplication {
        application { routerLabRoutes(mapOf("vic" to vicService())) }

        // Diacritic query text passes the allowlist (Ōtāhuhu, Anděl, …);
        // markup still doesn't.
        val unicodeQ = client.get("/internal/vic/stops/search?q=" +
            java.net.URLEncoder.encode("Ōtāhuhu", "UTF-8"))
        assertEquals(HttpStatusCode.OK, unicodeQ.status)

        val markup = client.get("/internal/vic/stops/search?q=%3Cb%3E")
        assertEquals(HttpStatusCode.BadRequest, markup.status)
    }

    // ---- Gating ------------------------------------------------------------

    @Test
    fun `router lab is 404 when dev gate is off`() = testApplication {
        application { configureRouterLabRoutes() }

        assertEquals(HttpStatusCode.NotFound, client.get("/internal/router-lab").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/internal/vic/stops/search?q=stop").status)
    }

    @Test
    fun `router lab serves html when dev gate is on`() = testApplication {
        environment {
            config = MapApplicationConfig("bff.devPassthrough" to "true")
        }
        application { configureRouterLabRoutes() }

        val response = client.get("/internal/router-lab")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.contentType()?.match(ContentType.Text.Html) == true)
        assertContains(response.bodyAsText(), "Router Lab")
        // No VIC model attribute in this app instance -> search not registered.
        assertEquals(HttpStatusCode.NotFound, client.get("/internal/vic/stops/search?q=stop").status)
    }

    /** Serves one canned JourneyList; everything else is unused by these routes. */
    private class StubNswClient : NswClient {
        override suspend fun getTripProto(
            originStopId: String,
            destinationStopId: String,
            depArr: String,
            date: String?,
            time: String?,
            excludedModes: Set<Int>,
        ): JourneyList = JourneyList(
            journeys = listOf(
                JourneyCardInfo(
                    time_text = "in 5 mins",
                    origin_time = "7:41am",
                    origin_utc_date_time = "2026-03-02T20:41:00Z",
                    destination_time = "8:05am",
                    destination_utc_date_time = "2026-03-02T21:05:00Z",
                    travel_time = "24 mins",
                )
            )
        )

        override suspend fun healthCheck(): Boolean = error("unused")
        override suspend fun getTrip(
            originStopId: String, destinationStopId: String, depArr: String,
            date: String?, time: String?, excludedModes: Set<Int>,
        ): TripResponse = error("unused")

        override suspend fun getTripRaw(
            originStopId: String, destinationStopId: String, depArr: String,
            date: String?, time: String?, excludedModes: Set<Int>,
        ): String = error("unused")

        override suspend fun getDeparturesRaw(stopId: String, date: String?, time: String?): String =
            error("unused")

        override suspend fun getCarparkRaw(facilityId: String?): String = error("unused")
        override suspend fun getGtfsRealtimeRaw(version: Int, feed: String): ByteArray = error("unused")
        override suspend fun getVehiclePositionsRaw(feed: String, version: Int): ByteArray = error("unused")
    }
}
