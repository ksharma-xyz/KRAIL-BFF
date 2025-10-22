package app.krail.bff

import app.krail.bff.client.nsw.NswClientImpl
import app.krail.bff.config.NswConfig
import com.codahale.metrics.MetricRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NswClientTest {
    @Test
    fun `healthCheck returns true on 200`() {
        val engine = MockEngine { respondOk("OK") }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val cfg = baseCfg()
        val metrics = MetricRegistry()
        val client = NswClientImpl(http, cfg, metrics)
        val ok = kotlinx.coroutines.runBlocking { client.healthCheck() }
        assertTrue(ok)
        assertEquals(1L, metrics.counter("nsw.health.result.success").count)
    }

    @Test
    fun `healthCheck counts 5xx and trips breaker after threshold`() {
        var calls = 0
        val engine = MockEngine {
            calls++
            when (calls) {
                1, 2 -> respond("boom", HttpStatusCode.InternalServerError)
                else -> respondOk("OK")
            }
        }
        val http = HttpClient(engine) {}
        val cfg = baseCfg().copy(breakerFailureThreshold = 2, breakerResetTimeoutMs = 10_000)
        val metrics = MetricRegistry()
        val client = NswClientImpl(http, cfg, metrics)

        val r1 = kotlinx.coroutines.runBlocking { client.healthCheck() }
        assertFalse(r1)
        assertEquals(1L, metrics.counter("nsw.health.result.upstream_5xx").count)

        val r2 = kotlinx.coroutines.runBlocking { client.healthCheck() }
        assertFalse(r2) // second failure trips breaker

        val r3 = kotlinx.coroutines.runBlocking { client.healthCheck() }
        assertFalse(r3) // skipped due to open breaker
        assertEquals(1L, metrics.counter("nsw.health.result.skipped").count)
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
