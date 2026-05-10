package app.krail.bff.routes

import app.krail.bff.client.nsw.NswBudgetExceededException
import app.krail.bff.client.nsw.NswClient
import app.krail.bff.client.nsw.NswUpstreamException
import app.krail.bff.di.configureDI
import app.krail.bff.plugins.configureCorrelation
import app.krail.bff.plugins.configureSerialization
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * Tests for the GET /v1/parking/availability batch endpoint.
 *
 * The batch endpoint fans out 1 request → N concurrent NSW calls and
 * returns a structured response with successful facilities and per-id
 * errors split. Validates:
 *
 *  - Happy path: 3 valid IDs, all return successfully → all in `facilities`.
 *  - Mixed valid + invalid format → invalid IDs go to `errors` with
 *    code=invalid_facility_id; valid IDs proceed.
 *  - NSW upstream 404 → goes to `errors` with code=upstream_404.
 *  - NSW upstream 5xx → goes to `errors` with code=upstream_error.
 *  - Daily budget exhausted → goes to `errors` with code=daily_budget_exceeded.
 *  - Missing/empty `ids` param → 400 missing_ids.
 *  - >20 IDs → 400 too_many_ids.
 *  - Duplicate IDs → silently deduped, NSW called once per unique id.
 *
 * Verified shape: { facilities: { id: NSW_body }, errors: { id: { code, message } }, correlationId }.
 */
class ParkingBatchTest {

    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun tearDown() {
        // Each testApplication boots its own Koin; clean up to avoid leakage
        // between tests in the same JVM run.
        runCatching { GlobalContext.stopKoin() }
    }

    @Test
    fun `happy path returns all three facilities under facilities key`() = testApplication {
        val nsw = StubNswClient()
        application {
            installRouting(nsw)
        }

        val response = client.get("/v1/parking/availability?ids=486,487,488")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val facilities = body["facilities"]!!.jsonObject
        val errors = body["errors"]!!.jsonObject

        assertEquals(setOf("486", "487", "488"), facilities.keys)
        assertTrue(errors.isEmpty(), "no errors expected, got $errors")

        // Each facility body is the parsed NSW JSON — assert the facility_id
        // field is preserved end-to-end.
        for (id in listOf("486", "487", "488")) {
            val facility = facilities[id]!!.jsonObject
            assertEquals(id, facility["facility_id"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `invalid id format goes to errors, valid ids still resolve`() = testApplication {
        val nsw = StubNswClient()
        application {
            installRouting(nsw)
        }

        val response = client.get("/v1/parking/availability?ids=486,@@bad@@,488")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val facilities = body["facilities"]!!.jsonObject
        val errors = body["errors"]!!.jsonObject

        assertEquals(setOf("486", "488"), facilities.keys)
        assertEquals(setOf("@@bad@@"), errors.keys)
        assertEquals("invalid_facility_id", errors["@@bad@@"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nsw 404 surfaces as upstream_404 in errors`() = testApplication {
        val nsw = StubNswClient(failingIds = mapOf("99999" to 404))
        application {
            installRouting(nsw)
        }

        val response = client.get("/v1/parking/availability?ids=486,99999")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(setOf("486"), body["facilities"]!!.jsonObject.keys)
        val errors = body["errors"]!!.jsonObject
        assertEquals("upstream_404", errors["99999"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nsw 5xx surfaces as upstream_error in errors`() = testApplication {
        val nsw = StubNswClient(failingIds = mapOf("486" to 503))
        application {
            installRouting(nsw)
        }

        val response = client.get("/v1/parking/availability?ids=486,487")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(setOf("487"), body["facilities"]!!.jsonObject.keys)
        val errors = body["errors"]!!.jsonObject
        assertEquals("upstream_error", errors["486"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `daily budget exceeded propagates per-id, not as a 500`() = testApplication {
        val nsw = StubNswClient(budgetExhausted = true)
        application {
            installRouting(nsw)
        }

        val response = client.get("/v1/parking/availability?ids=486,487,488")
        assertEquals(HttpStatusCode.OK, response.status, "batch returns 200 with errors-per-id")

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val errors = body["errors"]!!.jsonObject
        assertEquals(3, errors.size)
        for (id in listOf("486", "487", "488")) {
            assertEquals("daily_budget_exceeded", errors[id]!!.jsonObject["code"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `missing ids param is a 400`() = testApplication {
        val nsw = StubNswClient()
        application {
            installRouting(nsw)
        }

        val response = client.get("/v1/parking/availability")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "missing_ids")
    }

    @Test
    fun `too many ids returns 400 too_many_ids`() = testApplication {
        val nsw = StubNswClient()
        application {
            installRouting(nsw)
        }

        val ids = (1..25).joinToString(",")
        val response = client.get("/v1/parking/availability?ids=$ids")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "too_many_ids")
    }

    @Test
    fun `duplicate ids are deduped, nsw called once per unique id`() = testApplication {
        val nsw = StubNswClient()
        application {
            installRouting(nsw)
        }

        val response = client.get("/v1/parking/availability?ids=486,486,486,487")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val facilities = body["facilities"]!!.jsonObject
        assertEquals(setOf("486", "487"), facilities.keys)
        assertEquals(2, nsw.callCount, "expected exactly 2 NSW calls, saw ${nsw.callCount}")
    }

    // ============================================================
    // ?stopIds= variant — server-side stop→facility resolution
    // ============================================================

    @Test
    fun `stopIds variant resolves Tallawong to its three facilities (P1, P2, P3)`() = testApplication {
        val nsw = StubNswClient()
        application {
            installRouting(nsw)
        }

        val response = client.get("/v1/parking/availability?stopIds=2155384")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val stops = body["stops"]!!.jsonObject
        assertEquals(setOf("2155384"), stops.keys)

        val tallawong = stops["2155384"]!!.jsonObject
        val facilities = tallawong["facilities"]!!.jsonObject
        // Tallawong has facility IDs 26, 27, 28 — all three should resolve.
        assertEquals(setOf("26", "27", "28"), facilities.keys)
        assertTrue(tallawong["errors"]!!.jsonObject.isEmpty())
    }

    @Test
    fun `stopIds variant deduplicates aliased facility IDs (Hornsby has two stop IDs sharing facility 25)`() = testApplication {
        val nsw = StubNswClient()
        application {
            installRouting(nsw)
        }

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
        application {
            installRouting(nsw)
        }

        val response = client.get("/v1/parking/availability?stopIds=275010,9999999")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(setOf("275010"), body["stops"]!!.jsonObject.keys)
        // unknownStops is a JsonArray; extract values for assertion.
        val unknown = body["unknownStops"]!!.let {
            (it as kotlinx.serialization.json.JsonArray).map { e -> e.jsonPrimitive.content }
        }
        assertEquals(listOf("9999999"), unknown)
    }

    @Test
    fun `NSW namespace prefix is stripped before lookup`() = testApplication {
        val nsw = StubNswClient()
        application {
            installRouting(nsw)
        }

        val response = client.get("/v1/parking/availability?stopIds=NSW:213110")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        // Response key preserves whatever the client sent (the namespaced form),
        // but the lookup resolved against the canonical "213110" -> facility 486.
        val stops = body["stops"]!!.jsonObject
        assertEquals(setOf("NSW:213110"), stops.keys)
        val facilities = stops["NSW:213110"]!!.jsonObject["facilities"]!!.jsonObject
        assertEquals(setOf("486"), facilities.keys)
    }

    @Test
    fun `partial NSW failure on one of three facilities surfaces in per-stop errors`() = testApplication {
        // Tallawong has facilities 26, 27, 28. Make NSW fail on 27 only.
        val nsw = StubNswClient(failingIds = mapOf("27" to 503))
        application {
            installRouting(nsw)
        }

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
    fun `missing stopIds is a 400`() = testApplication {
        val nsw = StubNswClient()
        application {
            installRouting(nsw)
        }

        // ?stopIds= present but empty — handled by the empty-after-trim path.
        val response = client.get("/v1/parking/availability?stopIds=")
        // Empty stopIds means no `stopIds` param in the query, since
        // request.queryParameters["stopIds"] returns null for empty.
        // So it falls through to handleParkingBatch which 400s on missing ids.
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `too many stopIds returns 400 too_many_stop_ids`() = testApplication {
        val nsw = StubNswClient()
        application {
            installRouting(nsw)
        }

        val many = (1..25).joinToString(",")
        val response = client.get("/v1/parking/availability?stopIds=$many")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "too_many_stop_ids")
    }

    @Test
    fun `stopIds takes precedence over ids when both are present`() = testApplication {
        val nsw = StubNswClient()
        application {
            installRouting(nsw)
        }

        // ?ids= would resolve to facility 999 directly; ?stopIds= resolves
        // 213110 → facility 486. The response shape proves which path won.
        val response = client.get("/v1/parking/availability?stopIds=213110&ids=999")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        // Stop-id path produces a "stops" wrapper; facility-id path produces "facilities" at top level.
        assertNotNull(body["stops"], "?stopIds= path should win when both are present")
    }

    // ============================================================
    // existing ?ids= tests below (unchanged)
    // ============================================================

    @Test
    fun `correlationId is included in successful and partial-failure responses`() = testApplication {
        val nsw = StubNswClient()
        application {
            installRouting(nsw)
        }

        // Correlation plugin requires a valid UUID; non-UUID values are
        // ignored and a fresh one is generated. Use a real UUID so we can
        // verify echo behavior.
        val incomingId = "942cf2c5-ef3d-42e0-93c9-be120ecd1410"
        val response = client.get("/v1/parking/availability?ids=486") {
            headers.append("X-Request-Id", incomingId)
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val correlationId = body["correlationId"]?.jsonPrimitive?.content
        assertNotNull(correlationId, "correlationId should be present in batch response")
        // The Correlation plugin echoes the inbound id when it's a valid UUID.
        assertEquals(incomingId, correlationId)
    }

    // -------- Stub plumbing --------

    /**
     * Test stub for NswClient. Returns synthetic carpark JSON for any id
     * unless the id is in [failingIds] (in which case it throws an
     * NswUpstreamException with the configured status), or [budgetExhausted]
     * is true (in which case every call throws NswBudgetExceededException).
     */
    private class StubNswClient(
        private val failingIds: Map<String, Int> = emptyMap(),
        private val budgetExhausted: Boolean = false,
    ) : NswClient {
        var callCount = 0
            private set

        override suspend fun getCarparkRaw(facilityId: String?): String {
            callCount++
            if (budgetExhausted) {
                throw NswBudgetExceededException()
            }
            val status = failingIds[facilityId]
            if (status != null) {
                throw NswUpstreamException(
                    statusCode = status,
                    message = "NSW carpark returned $status ",
                    responseBody = "",
                )
            }
            // Minimal synthetic body that mirrors NSW's shape.
            return """
                {"facility_id":"$facilityId","facility_name":"Test $facilityId","spots":"100","zones":[]}
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

    /**
     * Wires Koin with the stub NswClient and just enough plugins to test
     * the batch endpoint end-to-end. Mirrors the production module order
     * for the things that touch this endpoint (Correlation → Serialization
     * → ParkingRoutes).
     */
    private fun io.ktor.server.application.Application.installRouting(stub: NswClient) {
        configureDI()
        // Override the NswClient binding with the test stub.
        org.koin.core.context.GlobalContext.get().loadModules(
            listOf(module { single<NswClient>(createdAtStart = false) { stub } }),
            allowOverride = true,
        )
        configureCorrelation()
        configureSerialization()
        configureParkingRoutes()
    }
}
