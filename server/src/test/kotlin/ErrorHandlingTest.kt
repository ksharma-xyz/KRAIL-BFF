package app.krail.bff

import app.krail.bff.plugins.Headers
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ErrorHandlingTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `404 returns error envelope with correlationId`() = testApplication {
        application { module() }
        val response = client.get("/does-not-exist")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertNotNull(response.headers[Headers.REQUEST_ID]) // correlationId in header

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("false", body["success"]?.jsonPrimitive?.content)

        val error = body["error"]!!.jsonObject
        assertEquals("not_found", error["code"]?.jsonPrimitive?.content)
        assertEquals("Resource not found", error["message"]?.jsonPrimitive?.content)

        assertNotNull(body["correlationId"]?.jsonPrimitive?.content) // correlationId in body
    }

    @Test
    fun `400 BadRequest returns error envelope`() = testApplication {
        application {
            module()
            // Add a test route that throws BadRequestException
            routing {
                get("/bad") {
                    throw BadRequestException("Invalid parameter: id must be numeric")
                }
            }
        }

        val response = client.get("/bad")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertNotNull(response.headers[Headers.REQUEST_ID])

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("false", body["success"]?.jsonPrimitive?.content)

        val error = body["error"]!!.jsonObject
        assertEquals("bad_request", error["code"]?.jsonPrimitive?.content)
        assertEquals("Invalid parameter: id must be numeric", error["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `500 unhandled exception returns error envelope`() = testApplication {
        application {
            module()
            // Add a test route that throws unhandled exception
            routing {
                get("/boom") {
                    throw RuntimeException("Something went wrong")
                }
            }
        }

        val response = client.get("/boom")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertNotNull(response.headers[Headers.REQUEST_ID])

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("false", body["success"]?.jsonPrimitive?.content)

        val error = body["error"]!!.jsonObject
        assertEquals("internal_error", error["code"]?.jsonPrimitive?.content)
        assertEquals("Internal server error", error["message"]?.jsonPrimitive?.content)
        // Note: we don't expose internal error details to clients
    }
}

