package app.krail.bff.plugins

import app.krail.bff.model.ErrorDetails
import app.krail.bff.model.ErrorEnvelope
import app.krail.bff.util.correlationIdOrNull
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*

/**
 * Configures error handling using StatusPages to map exceptions and HTTP status codes
 * to a consistent error envelope format with correlationId.
 */
fun Application.configureErrorHandling() {
    install(StatusPages) {
        // Map 404 Not Found
        status(HttpStatusCode.NotFound) { call, status ->
            val envelope = ErrorEnvelope(
                error = ErrorDetails(
                    code = "not_found",
                    message = "Resource not found"
                ),
                correlationId = call.correlationIdOrNull()
            )
            call.respond(status, envelope)
        }

        // Map BadRequestException (400)
        exception<BadRequestException> { call, cause ->
            val envelope = ErrorEnvelope(
                error = ErrorDetails(
                    code = "bad_request",
                    message = cause.message ?: "Bad request"
                ),
                correlationId = call.correlationIdOrNull()
            )
            call.respond(HttpStatusCode.BadRequest, envelope)
        }

        // Map unhandled exceptions to 500 Internal Server Error
        exception<Throwable> { call, cause ->
            // Log for server-side visibility
            call.application.log.error("Unhandled exception for ${call.request.uri}", cause)

            val envelope = ErrorEnvelope(
                error = ErrorDetails(
                    code = "internal_error",
                    message = "Internal server error"
                ),
                correlationId = call.correlationIdOrNull()
            )
            call.respond(HttpStatusCode.InternalServerError, envelope)
        }
    }
}
