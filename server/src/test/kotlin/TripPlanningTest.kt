package app.krail.bff

import app.krail.bff.client.nsw.NswClientImpl
import app.krail.bff.config.NswConfig
import app.krail.bff.model.TripResponse
import com.codahale.metrics.MetricRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TripPlanningTest {

    @Test
    fun `getTrip calls correct endpoint with required parameters`() {
        var capturedUrl: String? = null
        val mockResponse = """
            {
                "version": "10.2.1.42",
                "journeys": []
            }
        """.trimIndent()

        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                content = mockResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val http = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        val cfg = baseCfg()
        val metrics = MetricRegistry()
        val client = NswClientImpl(http, cfg, metrics)

        val response = kotlinx.coroutines.runBlocking {
            client.getTrip(
                originStopId = "10101100",
                destinationStopId = "10101328",
                depArr = "dep"
            )
        }

        assertNotNull(capturedUrl)
        assertTrue(capturedUrl!!.contains("/v1/tp/trip"))
        assertTrue(capturedUrl!!.contains("name_origin=10101100"))
        assertTrue(capturedUrl!!.contains("name_destination=10101328"))
        assertTrue(capturedUrl!!.contains("depArrMacro=dep"))
        assertNotNull(response)
        assertEquals("10.2.1.42", response.version)
        assertEquals(1L, metrics.counter("nsw.trip.success").count)
    }

    @Test
    fun `getTrip with excluded modes adds correct parameters`() {
        var capturedUrl: String? = null
        val mockResponse = """{"version": "10.2.1.42", "journeys": []}"""

        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                content = mockResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val http = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        val cfg = baseCfg()
        val metrics = MetricRegistry()
        val client = NswClientImpl(http, cfg, metrics)

        kotlinx.coroutines.runBlocking {
            client.getTrip(
                originStopId = "10101100",
                destinationStopId = "10101328",
                excludedModes = setOf(1, 4) // Exclude Train and Light Rail
            )
        }

        assertNotNull(capturedUrl)
        assertTrue(capturedUrl!!.contains("excludedMeans=checkbox"))
        assertTrue(capturedUrl!!.contains("exclMOT_1=1"))
        assertTrue(capturedUrl!!.contains("exclMOT_4=4"))
    }

    @Test
    fun `getTrip tracks metrics on error`() {
        val engine = MockEngine {
            respond(
                content = "Server Error",
                status = HttpStatusCode.InternalServerError
            )
        }

        val http = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        val cfg = baseCfg()
        val metrics = MetricRegistry()
        val client = NswClientImpl(http, cfg, metrics)

        try {
            kotlinx.coroutines.runBlocking {
                client.getTrip(
                    originStopId = "10101100",
                    destinationStopId = "10101328"
                )
            }
        } catch (_: Exception) {
            // Expected to fail
        }

        assertEquals(1L, metrics.counter("nsw.trip.error").count)
    }

    private fun baseCfg() = NswConfig(
        baseUrl = "https://api.transport.nsw.gov.au",
        apiKey = "test-key",
        connectTimeoutMs = 1000,
        readTimeoutMs = 1000,
        retryMaxAttempts = 0,
        retryBackoffMs = 10,
        breakerFailureThreshold = 2,
        breakerResetTimeoutMs = 100
    )
}

