package app.krail.bff.plugins

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OriginTokenGateTest {
    @Test
    fun `gate disabled when CF_ORIGIN_TOKEN unset`() = testApplication {
        application {
            // bff.cfOriginToken left unset / blank
            install(ContentNegotiation) { json() }; configureOriginTokenGate()
            routing { get("/x") { call.respondText("ok") } }
        }
        // No CF-Origin-Token header — should pass when gate disabled.
        val r = client.get("/x")
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test
    fun `missing header rejected when gate active`() = testApplication {
        application {
            (environment.config as MapApplicationConfig).put("bff.cfOriginToken", "secret-abc-123")
            install(ContentNegotiation) { json() }; configureOriginTokenGate()
            routing { get("/x") { call.respondText("ok") } }
        }
        val r = client.get("/x")
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertTrue(r.bodyAsText().contains("forbidden"))
    }

    @Test
    fun `mismatched token rejected`() = testApplication {
        application {
            (environment.config as MapApplicationConfig).put("bff.cfOriginToken", "secret-abc-123")
            install(ContentNegotiation) { json() }; configureOriginTokenGate()
            routing { get("/x") { call.respondText("ok") } }
        }
        val r = client.get("/x") { header("CF-Origin-Token", "wrong") }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun `matching token passes`() = testApplication {
        application {
            (environment.config as MapApplicationConfig).put("bff.cfOriginToken", "secret-abc-123")
            install(ContentNegotiation) { json() }; configureOriginTokenGate()
            routing { get("/x") { call.respondText("ok") } }
        }
        val r = client.get("/x") { header("CF-Origin-Token", "secret-abc-123") }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test
    fun `health and root paths exempt`() = testApplication {
        application {
            (environment.config as MapApplicationConfig).put("bff.cfOriginToken", "secret-abc-123")
            install(ContentNegotiation) { json() }; configureOriginTokenGate()
            routing {
                get("/health") { call.respondText("h") }
                get("/ready") { call.respondText("r") }
                get("/") { call.respondText("root") }
            }
        }
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
        assertEquals(HttpStatusCode.OK, client.get("/ready").status)
        assertEquals(HttpStatusCode.OK, client.get("/").status)
    }
}
