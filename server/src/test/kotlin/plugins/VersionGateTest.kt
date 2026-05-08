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

class VersionGateTest {
    @Test
    fun `gate disabled when MIN_APP_VERSION is 0_0_0 or unset`() = testApplication {
        application {
            (environment.config as MapApplicationConfig).put("bff.minAppVersion", "0.0.0")
            install(ContentNegotiation) { json() }; configureVersionGate()
            routing { get("/x") { call.respondText("ok") } }
        }
        val r = client.get("/x")
        assertEquals(HttpStatusCode.OK, r.status, "no header required when gate disabled")
        assertEquals("ok", r.bodyAsText())
    }

    @Test
    fun `missing header rejected when gate active`() = testApplication {
        application {
            (environment.config as MapApplicationConfig).put("bff.minAppVersion", "1.5.0")
            install(ContentNegotiation) { json() }; configureVersionGate()
            routing { get("/x") { call.respondText("ok") } }
        }
        val r = client.get("/x")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("missing_version"))
    }

    @Test
    fun `malformed header rejected with invalid_version`() = testApplication {
        application {
            (environment.config as MapApplicationConfig).put("bff.minAppVersion", "1.5.0")
            install(ContentNegotiation) { json() }; configureVersionGate()
            routing { get("/x") { call.respondText("ok") } }
        }
        val r = client.get("/x") { header("X-Krail-Version", "not-a-version") }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("invalid_version"))
    }

    @Test
    fun `version below floor returns 426 Upgrade Required`() = testApplication {
        application {
            (environment.config as MapApplicationConfig).put("bff.minAppVersion", "2.0.0")
            install(ContentNegotiation) { json() }; configureVersionGate()
            routing { get("/x") { call.respondText("ok") } }
        }
        val r = client.get("/x") { header("X-Krail-Version", "1.5.0") }
        assertEquals(HttpStatusCode.UpgradeRequired, r.status)
        assertTrue(r.bodyAsText().contains("upgrade_required"))
    }

    @Test
    fun `version at or above floor passes`() = testApplication {
        application {
            (environment.config as MapApplicationConfig).put("bff.minAppVersion", "1.5.0")
            install(ContentNegotiation) { json() }; configureVersionGate()
            routing { get("/x") { call.respondText("ok") } }
        }
        assertEquals(HttpStatusCode.OK, client.get("/x") { header("X-Krail-Version", "1.5.0") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/x") { header("X-Krail-Version", "1.5.1") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/x") { header("X-Krail-Version", "2.0.0") }.status)
    }

    @Test
    fun `health and ready paths exempt from gate`() = testApplication {
        application {
            (environment.config as MapApplicationConfig).put("bff.minAppVersion", "1.5.0")
            install(ContentNegotiation) { json() }; configureVersionGate()
            routing {
                get("/health") { call.respondText("h") }
                get("/ready") { call.respondText("r") }
                get("/") { call.respondText("root") }
            }
        }
        // No X-Krail-Version header — gate should not fire on exempt paths.
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
        assertEquals(HttpStatusCode.OK, client.get("/ready").status)
        assertEquals(HttpStatusCode.OK, client.get("/").status)
    }
}
