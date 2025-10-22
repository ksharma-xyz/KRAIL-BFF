package app.krail.bff.client.nsw

import app.krail.bff.config.NswConfig
import app.krail.bff.model.TripResponse
import com.codahale.metrics.MetricRegistry
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

interface NswClient {
    suspend fun healthCheck(): Boolean

    /**
     * Call NSW Transport trip planning API.
     * @param originStopId Origin stop/station ID
     * @param destinationStopId Destination stop/station ID
     * @param depArr Departure or arrival time mode ("dep" or "arr")
     * @param date Date in YYYYMMDD format (optional, defaults to today)
     * @param time Time in HHmm format (optional, defaults to now)
     * @param excludedModes Set of transport mode IDs to exclude (1=Train, 4=Light Rail, 5=Bus, 7=Coach, 9=Ferry, 11=School Bus)
     */
    suspend fun getTrip(
        originStopId: String,
        destinationStopId: String,
        depArr: String = "dep",
        date: String? = null,
        time: String? = null,
        excludedModes: Set<Int> = emptySet()
    ): TripResponse
}

class NswClientImpl(
    private val http: HttpClient,
    private val config: NswConfig,
    metrics: MetricRegistry
) : NswClient {

    private val logger = LoggerFactory.getLogger(NswClientImpl::class.java)

    private val failures = AtomicInteger(0)
    private val openUntilEpochMs = AtomicLong(0)

    private val timer = metrics.timer("nsw.health.duration")
    private val countSuccess = metrics.counter("nsw.health.result.success")
    private val countUp4xx = metrics.counter("nsw.health.result.upstream_4xx")
    private val countUp5xx = metrics.counter("nsw.health.result.upstream_5xx")
    private val countException = metrics.counter("nsw.health.result.exception")
    private val countSkipped = metrics.counter("nsw.health.result.skipped")

    // Trip API metrics
    private val tripTimer = metrics.timer("nsw.trip.duration")
    private val tripSuccess = metrics.counter("nsw.trip.success")
    private val tripError = metrics.counter("nsw.trip.error")

    override suspend fun healthCheck(): Boolean {
        val now = System.currentTimeMillis()
        val openUntil = openUntilEpochMs.get()
        if (now < openUntil) {
            countSkipped.inc()
            logger.debug("Health check skipped - circuit breaker open until {}", openUntil)
            return false
        }

        val ctx = timer.time()
        return try {
            val url = config.baseUrl.trimEnd('/') + "/"
            logger.debug("Performing health check to: {}", url)
            val response: HttpResponse = http.get(url)
            val ok = response.status.isSuccess()
            if (ok) {
                failures.set(0)
                countSuccess.inc()
                logger.debug("Health check succeeded")
                true
            } else {
                // count failure and maybe trip breaker
                logger.warn("Health check failed with status: {}", response.status.value)
                onFailure(response.status.value)
                false
            }
        } catch (e: Throwable) {
            logger.error("Health check failed with exception", e)
            onFailure(null)
            false
        } finally {
            ctx.stop()
        }
    }

    override suspend fun getTrip(
        originStopId: String,
        destinationStopId: String,
        depArr: String,
        date: String?,
        time: String?,
        excludedModes: Set<Int>
    ): TripResponse {
        val ctx = tripTimer.time()
        return try {
            val baseUrl = "${config.baseUrl.trimEnd('/')}/v1/tp/trip"

            logger.info("=" .repeat(80))
            logger.info("🚀 NSW TRIP API REQUEST")
            logger.info("=" .repeat(80))
            logger.info("📋 Input Parameters:")
            logger.info("   Origin Stop ID: {}", originStopId)
            logger.info("   Destination Stop ID: {}", destinationStopId)
            logger.info("   DepArr Mode: {}", depArr)
            logger.info("   Date: {}", date ?: "not set")
            logger.info("   Time: {}", time ?: "not set")
            logger.info("   Excluded Modes: {}", excludedModes)
            logger.info("   Base URL: {}", baseUrl)
            logger.info("   API Key: {}***", config.apiKey.take(8))

            val response = http.get(baseUrl) {
                // Add Authorization header for NSW Transport API
                headers.append("Authorization", "apikey ${config.apiKey}")

                url {
                    parameters.append("name_origin", originStopId)
                    parameters.append("name_destination", destinationStopId)
                    parameters.append("depArrMacro", depArr)
                    date?.let { parameters.append("itdDate", it) }
                    time?.let { parameters.append("itdTime", it) }

                    parameters.append("type_destination", "any")
                    parameters.append("calcNumberOfTrips", "6")
                    parameters.append("type_origin", "any")
                    parameters.append("TfNSWTR", "true")
                    parameters.append("version", "10.2.1.42")
                    parameters.append("coordOutputFormat", "EPSG:4326")
                    parameters.append("itOptionsActive", "1")
                    parameters.append("computeMonomodalTripBicycle", "false")
                    parameters.append("cycleSpeed", "16")
                    parameters.append("useElevationData", "1")
                    parameters.append("outputFormat", "rapidJSON")

                    // Add excluded transport modes
                    if (excludedModes.isNotEmpty()) {
                        parameters.append("excludedMeans", "checkbox")
                        if (1 in excludedModes) parameters.append("exclMOT_1", "1")
                        if (2 in excludedModes) parameters.append("exclMOT_2", "2")
                        if (4 in excludedModes) parameters.append("exclMOT_4", "4")
                        if (5 in excludedModes || 11 in excludedModes) {
                            parameters.append("exclMOT_5", "5")
                            parameters.append("exclMOT_11", "11")
                        }
                        if (7 in excludedModes) parameters.append("exclMOT_7", "7")
                        if (9 in excludedModes) parameters.append("exclMOT_9", "9")
                    }
                }
            }

            // Log the actual request URL from the response
            logger.info("📍 Complete Request URL: {}", response.call.request.url.toString())

            // Log all request headers (masking API key)
            logger.info("📤 REQUEST HEADERS:")
            response.call.request.headers.entries().forEach { entry ->
                val displayValue = if (entry.key.equals("Authorization", ignoreCase = true)) {
                    entry.value.joinToString(", ") { "apikey ${config.apiKey.take(8)}***" }
                } else {
                    entry.value.joinToString(", ")
                }
                logger.info("   {} = {}", entry.key, displayValue)
            }
            logger.info("   Method: {}", response.call.request.method.value)

            logger.info("=" .repeat(80))
            logger.info("📥 NSW TRIP API RESPONSE")
            logger.info("=" .repeat(80))
            logger.info("HTTP Status: {}", response.status.value)
            logger.info("HTTP Status Description: {}", response.status.description)

            // Log all response headers
            logger.info("📦 RESPONSE HEADERS:")
            response.headers.entries().forEach { entry ->
                logger.info("   {} = {}", entry.key, entry.value.joinToString(", "))
            }

            // Get raw response body as text for logging
            val responseText = response.bodyAsText()
            logger.info("📄 RAW RESPONSE BODY (length: {} bytes):", responseText.length)
            logger.info(responseText.take(2000))
            if (responseText.length > 2000) {
                logger.info("... (truncated, showing first 2000 of {} chars)", responseText.length)
            }
            logger.info("=" .repeat(80))

            // Check HTTP status code before parsing
            if (!response.status.isSuccess()) {
                logger.error("❌ NSW API returned error status: {} - {}", response.status.value, response.status.description)
                logger.error("Error response body: {}", responseText)
                tripError.inc()
                throw IllegalStateException("NSW API returned ${response.status.value}: ${response.status.description}. Body: $responseText")
            }

            // Parse using kotlinx.serialization
            val json = Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                isLenient = true
            }
            val tripResponse: TripResponse = json.decodeFromString(responseText)

            tripSuccess.inc()

            logger.info("✅ Parsed Trip Response - journeys count: {}, has error: {}",
                tripResponse.journeys?.size ?: 0,
                tripResponse.error != null
            )

            if (tripResponse.error != null) {
                logger.warn("⚠️ Trip API returned error: {}", tripResponse.error.message)
            }

            tripResponse
        } catch (e: Throwable) {
            tripError.inc()
            logger.error("❌ Failed to fetch trip from NSW API - origin: {}, destination: {}",
                originStopId, destinationStopId, e)
            throw e
        } finally {
            ctx.stop()
        }
    }

    private fun onFailure(statusCode: Int?) {
        if (statusCode != null) {
            if (statusCode in 400..499) countUp4xx.inc() else if (statusCode in 500..599) countUp5xx.inc() else countException.inc()
        } else {
            countException.inc()
        }
        val now = System.currentTimeMillis()
        val n = failures.incrementAndGet()
        if (n >= config.breakerFailureThreshold) {
            openUntilEpochMs.set(now + config.breakerResetTimeoutMs)
            failures.set(0)
            logger.warn("Circuit breaker opened - failure threshold reached: {}", n)
        }
    }
}
