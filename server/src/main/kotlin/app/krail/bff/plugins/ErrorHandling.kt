package app.krail.bff.plugins

import app.krail.bff.client.nsw.NswBudgetExceededException
import app.krail.bff.client.nsw.NswUpstreamException
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
 * to a consistent error envelope format with correlationId. Upstream details (response
 * bodies, stack traces) are intentionally never reflected to the client.
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

        // NSW upstream returned non-2xx — generic message; never leak the upstream body.
        exception<NswUpstreamException> { call, cause ->
            call.application.log.warn(
                "NSW upstream error: status={} body={}",
                cause.statusCode,
                cause.responseBody.take(500),
            )
            val envelope = ErrorEnvelope(
                error = ErrorDetails(
                    code = "upstream_error",
                    message = "Upstream service is currently unavailable"
                ),
                correlationId = call.correlationIdOrNull()
            )
            call.respond(HttpStatusCode.BadGateway, envelope)
        }

        // BFF self-imposed daily NSW call budget exceeded — back off until reset.
        exception<NswBudgetExceededException> { call, _ ->
            call.response.headers.append("Retry-After", "3600")
            val envelope = ErrorEnvelope(
                error = ErrorDetails(
                    code = "service_temporarily_limited",
                    message = "Daily upstream call budget exhausted; please retry later"
                ),
                correlationId = call.correlationIdOrNull()
            )
            call.respond(HttpStatusCode.ServiceUnavailable, envelope)
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
