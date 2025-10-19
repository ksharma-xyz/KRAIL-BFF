package app.krail.bff.routes

import app.krail.bff.client.nsw.NswClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Application.configureAdministration() {
    val nswClient by inject<NswClient>()

    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "up"))
        }
        get("/ready") {
            val upstreamOk = try {
                nswClient.healthCheck()
            } catch (_: Throwable) {
                false
            }
            if (upstreamOk) {
                call.respond(HttpStatusCode.OK, mapOf("status" to "ready", "nsw" to "up"))
            } else {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to "degraded", "nsw" to "down"))
            }
        }
    }
}
