package app.krail.bff

import app.krail.bff.plugins.Headers
import app.krail.bff.plugins.MobileAttributes
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MobileAnalyticsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `captures mobile headers when provided`() = testApplication {
        application {
            module()
            routing {
                get("/ctx") {
                    val ctx = call.attributes[MobileAttributes.Key]
                    call.respond(ctx)
                }
            }
        }

        val response = client.get("/ctx") {
            header(Headers.DEVICE_ID, "dev-123")
            header(Headers.DEVICE_MODEL, "iPhone15,3")
            header(Headers.OS_NAME, "iOS")
            header(Headers.OS_VERSION, "18.1")
            header(Headers.APP_VERSION, "2.3.4")
            header(Headers.CLIENT_REGION, "US")
            header(Headers.NETWORK_TYPE, "wifi")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("dev-123", body["deviceId"]?.jsonPrimitive?.content)
        assertEquals("iPhone15,3", body["deviceModel"]?.jsonPrimitive?.content)
        assertEquals("iOS", body["osName"]?.jsonPrimitive?.content)
        assertEquals("18.1", body["osVersion"]?.jsonPrimitive?.content)
        assertEquals("2.3.4", body["appVersion"]?.jsonPrimitive?.content)
        assertEquals("US", body["clientRegion"]?.jsonPrimitive?.content)
        assertEquals("wifi", body["networkType"]?.jsonPrimitive?.content)
    }

    @Test
    fun `gracefully handles missing mobile headers`() = testApplication {
        application {
            module()
            routing {
                get("/ctx") {
                    val ctx = call.attributes[MobileAttributes.Key]
                    call.respond(ctx)
                }
            }
        }

        val response = client.get("/ctx")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject

        fun isNullOrMissing(key: String) = body[key] == null || body[key] is JsonNull

        assertTrue(isNullOrMissing("deviceId"))
        assertTrue(isNullOrMissing("deviceModel"))
        assertTrue(isNullOrMissing("osName"))
        assertTrue(isNullOrMissing("osVersion"))
        assertTrue(isNullOrMissing("appVersion"))
        assertTrue(isNullOrMissing("clientRegion"))
        assertTrue(isNullOrMissing("networkType"))
    }
}
