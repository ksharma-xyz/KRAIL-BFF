package app.krail.bff

import app.krail.bff.plugins.Headers
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CorrelationTest {

    @Test
    fun `echoes provided X-Request-Id header`() = testApplication {
        application { module() }
        val provided = "123e4567-e89b-12d3-a456-426614174000"
        val response = client.get("/") {
            header(Headers.REQUEST_ID, provided)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(provided, response.headers[Headers.REQUEST_ID]) // echoed back
    }

    @Test
    fun `generates X-Request-Id when missing`() = testApplication {
        application { module() }
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        val corr = response.headers[Headers.REQUEST_ID]
        assertNotNull(corr)
        // very basic UUID shape check
        assertTrue(corr.contains("-"))
    }
}
