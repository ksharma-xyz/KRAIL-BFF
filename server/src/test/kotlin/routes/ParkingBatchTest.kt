package app.krail.bff.routes

import app.krail.bff.client.nsw.NswBudgetExceededException
import app.krail.bff.client.nsw.NswClient
import app.krail.bff.client.nsw.NswUpstreamException
import app.krail.bff.di.configureDI
import app.krail.bff.plugins.configureCorrelation
import app.krail.bff.plugins.configureSerialization
import app.krail.bff.proto.ParkingAvailabilityResponse
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the parking batch endpoints — JSON + proto.
 *
 * Both endpoints accept `?stopIds=...` only (the `?ids=` mode was
 * removed; KRAIL exclusively uses the stop-id resolution path). The
 * BFF resolves stop IDs to facility IDs server-side and fans out
 * concurrent NSW calls.
 *
 *  - GET /v1/parking/availability?stopIds=...               JSON
 *  - GET /api/v1/parking/availability-proto?stopIds=...     binary proto
 *
 * Coverage:
 *
 *  - Happy path: known stops resolve, all facilities populated.
 *  - Aliasing: two stops sharing a facility (Hornsby) → 1 NSW call.
 *  - Unknown stops: go to `unknownStops`, known stops still resolve.
 *  - NSW: prefix is stripped before lookup.
 *  - Per-facility upstream failures land in per-stop `errors`.
 *  - Bad input: missing / too-many stop IDs → 400.
 *  - Correlation ID echoed when caller sends a valid X-Request-Id.
 *  - Proto endpoint emits the same logical content as JSON.
 */
class ParkingBatchTest {

    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun tearDown() {
        runCatching { GlobalContext.stopKoin() }
    }

    // ============================================================
    // JSON endpoint — /v1/parking/availability?stopIds=
    // ============================================================

    @Test
    fun `Tallawong stop resolves to its three facilities (P1, P2, P3)`() = testApplication {
        val nsw = StubNswClient()
        application { installRouting(nsw) }

        val response = client.get("/v1/parking/availability?stopIds=2155384")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val stops = body["stops"]!!.jsonObject
        assertEquals(setOf("2155384"), stops.keys)

        val tallawong = stops["2155384"]!!.jsonObject
        val facilities = tallawong["facilities"]!!.jsonObject
        assertEquals(setOf("26", "27", "28"), facilities.keys)
        assertTrue(tallawong["errors"]!!.jsonObject.isEmpty())
    }

    @Test
    fun `aliased facility IDs are deduped — Hornsby has two stop IDs sharing facility 25`() = testApplication {
        val nsw = StubNswClient()
        application { installRouting(nsw) }

        // Stops 207720 and 207763 both alias facility 25 — NSW should be
        // called exactly ONCE for facility 25 across the whole batch.
        val response = client.get("/v1/parking/availability?stopIds=207720,207763")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val stops = body["stops"]!!.jsonObject
        assertEquals(setOf("207720", "207763"), stops.keys)
        for (stopId in listOf("207720", "207763")) {
            val facilities = stops[stopId]!!.jsonObject["facilities"]!!.jsonObject
            assertEquals(setOf("25"), facilities.keys)
        }
        assertEquals(1, nsw.callCount, "expected 1 NSW call for the shared facility, got ${nsw.callCount}")
    }

    @Test
    fun `unknown stop IDs go to unknownStops, known stops still resolve`() = testApplication {
        val nsw = StubNswClient()
        application { installRouting(nsw) }

        val response = client.get("/v1/parking/availability?stopIds=275010,9999999")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(setOf("275010"), body["stops"]!!.jsonObject.keys)
        val unknown = body["unknownStops"]!!.let {
            (it as kotlinx.serialization.json.JsonArray).map { e -> e.jsonPrimitive.content }
        }
        assertEquals(listOf("9999999"), unknown)
    }

    @Test
    fun `NSW namespace prefix is stripped before lookup`() = testApplication {
        val nsw = StubNswClient()
        application { installRouting(nsw) }

        val response = client.get("/v1/parking/availability?stopIds=NSW:213110")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val stops = body["stops"]!!.jsonObject
        // Response key preserves whatever the client sent (the namespaced
        // form), but the lookup resolved against the canonical "213110".
        assertEquals(setOf("NSW:213110"), stops.keys)
        val facilities = stops["NSW:213110"]!!.jsonObject["facilities"]!!.jsonObject
        assertEquals(setOf("486"), facilities.keys)
    }

    @Test
    fun `partial NSW failure surfaces in per-stop errors`() = testApplication {
        // Tallawong has facilities 26, 27, 28. Make NSW fail on 27 only.
        val nsw = StubNswClient(failingIds = mapOf("27" to 503))
        application { installRouting(nsw) }

        val response = client.get("/v1/parking/availability?stopIds=2155384")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val tallawong = body["stops"]!!.jsonObject["2155384"]!!.jsonObject
        assertEquals(setOf("26", "28"), tallawong["facilities"]!!.jsonObject.keys)
        val errors = tallawong["errors"]!!.jsonObject
        assertEquals(setOf("27"), errors.keys)
        assertEquals("upstream_error", errors["27"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `missing stopIds returns 400`() = testApplication {
        val nsw = StubNswClient()
        application { installRouting(nsw) }

        val response = client.get("/v1/parking/availability")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "missing_stop_ids")
    }

    @Test
    fun `too many stopIds returns 400 too_many_stop_ids`() = testApplication {
        val nsw = StubNswClient()
        application { installRouting(nsw) }

        val many = (1..25).joinToString(",")
        val response = client.get("/v1/parking/availability?stopIds=$many")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "too_many_stop_ids")
    }

    @Test
    fun `correlationId is echoed when caller sends a valid X-Request-Id`() = testApplication {
        val nsw = StubNswClient()
        application { installRouting(nsw) }

        // Correlation plugin requires a valid UUID; non-UUID values are
        // ignored and a fresh one is generated. Use a real UUID to verify echo.
        val incomingId = "942cf2c5-ef3d-42e0-93c9-be120ecd1410"
        val response = client.get("/v1/parking/availability?stopIds=2155384") {
            headers.append("X-Request-Id", incomingId)
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val correlationId = body["correlationId"]?.jsonPrimitive?.content
        assertNotNull(correlationId)
        assertEquals(incomingId, correlationId)
    }

    // ============================================================
    // Proto endpoint — /api/v1/parking/availability-proto?stopIds=
    // ============================================================

    @Test
    fun `proto endpoint returns ParkingAvailabilityResponse with stops populated`() = testApplication {
        val nsw = StubNswClient()
        application { installRouting(nsw) }

        val response = client.get("/api/v1/parking/availability-proto?stopIds=2155384")
        assertEquals(HttpStatusCode.OK, response.status)

        val proto = ParkingAvailabilityResponse.ADAPTER.decode(response.readRawBytes())

        assertTrue(proto.facilities.isEmpty(), "top-level facilities should be empty in stopIds mode")
        assertTrue(proto.errors.isEmpty(), "top-level errors should be empty in stopIds mode")
        assertTrue(proto.unknown_stops.isEmpty())
        assertEquals(setOf("2155384"), proto.stops.keys)

        val tallawong = proto.stops["2155384"]!!
        assertEquals(setOf("26", "27", "28"), tallawong.facilities.keys)
        assertTrue(tallawong.errors.isEmpty())

        // Spot-check the screen-shape mapping landed.
        val p1 = tallawong.facilities["26"]!!
        assertEquals("26", p1.facility_id)
        assertEquals("Test 26", p1.facility_name)
        assertEquals(100, p1.total_spots)
    }

    @Test
    fun `proto endpoint surfaces per-facility errors per-stop`() = testApplication {
        val nsw = StubNswClient(failingIds = mapOf("27" to 503))
        application { installRouting(nsw) }

        val response = client.get("/api/v1/parking/availability-proto?stopIds=2155384")
        assertEquals(HttpStatusCode.OK, response.status)

        val proto = ParkingAvailabilityResponse.ADAPTER.decode(response.readRawBytes())
        val tallawong = proto.stops["2155384"]!!

        assertEquals(setOf("26", "28"), tallawong.facilities.keys)
        assertEquals(setOf("27"), tallawong.errors.keys)
        assertEquals("upstream_error", tallawong.errors["27"]!!.code)
    }

    @Test
    fun `proto endpoint puts unknown stops in unknown_stops`() = testApplication {
        val nsw = StubNswClient()
        application { installRouting(nsw) }

        val response = client.get("/api/v1/parking/availability-proto?stopIds=275010,9999999")
        assertEquals(HttpStatusCode.OK, response.status)

        val proto = ParkingAvailabilityResponse.ADAPTER.decode(response.readRawBytes())
        assertEquals(setOf("275010"), proto.stops.keys)
        assertEquals(listOf("9999999"), proto.unknown_stops)
    }

    @Test
    fun `proto endpoint missing stopIds returns 400`() = testApplication {
        val nsw = StubNswClient()
        application { installRouting(nsw) }

        val response = client.get("/api/v1/parking/availability-proto")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "missing_stop_ids")
    }

    // -------- Stub plumbing --------

    private class StubNswClient(
        private val failingIds: Map<String, Int> = emptyMap(),
        private val budgetExhausted: Boolean = false,
    ) : NswClient {
        var callCount = 0
            private set

        override suspend fun getCarparkRaw(facilityId: String?): String {
            callCount++
            if (budgetExhausted) throw NswBudgetExceededException()
            val status = failingIds[facilityId]
            if (status != null) {
                throw NswUpstreamException(
                    statusCode = status,
                    message = "NSW carpark returned $status ",
                    responseBody = "",
                )
            }
            // Minimal synthetic body that mirrors NSW's shape — enough fields
            // for the JSON tests + the proto mapper to populate the proto.
            return """
                {"facility_id":"$facilityId","facility_name":"Test $facilityId","spots":"100","occupancy":{"total":"42"},"zones":[]}
            """.trimIndent()
        }

        override suspend fun healthCheck(): Boolean = true
        override suspend fun getTrip(
            originStopId: String, destinationStopId: String, depArr: String,
            date: String?, time: String?, excludedModes: Set<Int>,
        ) = throw UnsupportedOperationException("not used in batch test")
        override suspend fun getTripRaw(
            originStopId: String, destinationStopId: String, depArr: String,
            date: String?, time: String?, excludedModes: Set<Int>,
        ): String = throw UnsupportedOperationException("not used in batch test")
        override suspend fun getTripProto(
            originStopId: String, destinationStopId: String, depArr: String,
            date: String?, time: String?, excludedModes: Set<Int>,
        ) = throw UnsupportedOperationException("not used in batch test")
        override suspend fun getDeparturesRaw(stopId: String, date: String?, time: String?) =
            throw UnsupportedOperationException("not used in batch test")
        override suspend fun getGtfsRealtimeRaw(version: Int, feed: String) =
            throw UnsupportedOperationException("not used in batch test")
        override suspend fun getVehiclePositionsRaw(feed: String) =
            throw UnsupportedOperationException("not used in batch test")
    }

    private fun io.ktor.server.application.Application.installRouting(stub: NswClient) {
        configureDI()
        org.koin.core.context.GlobalContext.get().loadModules(
            listOf(module { single<NswClient>(createdAtStart = false) { stub } }),
            allowOverride = true,
        )
        configureCorrelation()
        configureSerialization()
        configureParkingRoutes()
    }
}
