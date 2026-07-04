package app.krail.bff.client.nsw

import app.krail.bff.config.NswConfig
import app.krail.bff.model.TripResponse
import app.krail.bff.mapper.JourneyListMapper
import app.krail.bff.proto.JourneyList
import com.codahale.metrics.MetricRegistry
import io.ktor.client.HttpClient
import io.ktor.client.call.body
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

    /**
     * Call NSW Transport trip planning API and return Protocol Buffer format.
     * @param originStopId Origin stop/station ID
     * @param destinationStopId Destination stop/station ID
     * @param depArr Departure or arrival time mode ("dep" or "arr")
     * @param date Date in YYYYMMDD format (optional, defaults to today)
     * @param time Time in HHmm format (optional, defaults to now)
     * @param excludedModes Set of transport mode IDs to exclude (1=Train, 4=Light Rail, 5=Bus, 7=Coach, 9=Ferry, 11=School Bus)
     */
    suspend fun getTripProto(
        originStopId: String,
        destinationStopId: String,
        depArr: String = "dep",
        date: String? = null,
        time: String? = null,
        excludedModes: Set<Int> = emptySet()
    ): JourneyList

    /**
     * Trip plan, **raw NSW JSON body** — verbatim pass-through.
     *
     * Use this for the `/v1/tp/trip` legacy endpoint. Unlike [getTrip],
     * this does NOT deserialize through [TripResponse], so every field NSW
     * returns survives — `coords`, `coupledTripsInfo`, `fare`, `interchanges`,
     * stop-level `coord`, `parent`, the lot. The typed model is necessarily
     * incomplete for a 200+ field schema; pass-through avoids silent loss.
     *
     * The proto endpoint ([getTripProto]) still uses the typed path because
     * its mapper requires structure. The future `/api/v1/trip/plan`
     * screen-shaped JSON endpoint will define its own shape from scratch.
     */
    suspend fun getTripRaw(
        originStopId: String,
        destinationStopId: String,
        depArr: String = "dep",
        date: String? = null,
        time: String? = null,
        excludedModes: Set<Int> = emptySet(),
    ): String

    /** Departure board for a stop. Returns raw NSW JSON body. */
    suspend fun getDeparturesRaw(stopId: String, date: String? = null, time: String? = null): String

    /** Park & ride facility list (omit facilityId) or single facility detail. Returns raw NSW JSON. */
    suspend fun getCarparkRaw(facilityId: String? = null): String

    /**
     * GTFS-Realtime feed (trip updates / alerts) — `/v{version}/gtfs/realtime/{feed}`.
     * Returns raw protobuf bytes; client decodes with the standard GTFS-RT schema.
     */
    suspend fun getGtfsRealtimeRaw(version: Int, feed: String): ByteArray

    /**
     * GTFS-Realtime vehicle positions — `/v{version}/gtfs/vehiclepos/{feed}`.
     * Most feeds live on v2; buses and nswtrains exist only on v1
     * (verified against the live API 2026-06-12). Returns raw protobuf bytes.
     */
    suspend fun getVehiclePositionsRaw(feed: String, version: Int = 2): ByteArray
}

class NswClientImpl(
    private val http: HttpClient,
    private val config: NswConfig,
    metrics: MetricRegistry,
    private val dailyBudget: NswDailyBudget,
) : NswClient {

    companion object {
        // Reused for every NSW response decode. Configuring a Json{} instance
        // is non-trivial (it builds a SerializersModule); hoisting it out of
        // the request path saves an allocation per call and lets the JIT inline
        // the configuration.
        private val NSW_JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            isLenient = true
        }

        // 80-char separator used in the verbose request/response diagnostic
        // logging. Was being recomputed via "=".repeat(80) on every NSW call
        // (8 allocations per trip-planner request); now constant.
        private const val LOG_SEPARATOR =
            "================================================================================"
    }

    private val logger = LoggerFactory.getLogger(NswClientImpl::class.java)

    /**
     * Query params for `/v1/tp/trip` — shared by [getTrip] (typed path) and
     * [getTripRaw] (pass-through path) so the two can never drift apart.
     *
     * Per TfNSW Trip Planner API spec, `exclMOT_<id>` value must always be "1"
     * (not the mode ID). See:
     * https://opendata.transport.nsw.gov.au/data/dataset/trip-planner-apis/resource/917c66c3-8123-4a0f-b1b1-b4220f32585d
     */
    private fun io.ktor.http.URLBuilder.appendTripPlanParams(
        originStopId: String,
        destinationStopId: String,
        depArr: String,
        date: String?,
        time: String?,
        excludedModes: Set<Int>,
    ) {
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

        if (excludedModes.isNotEmpty()) {
            parameters.append("excludedMeans", "checkbox")
            for (mode in intArrayOf(1, 2, 4, 5, 7, 9, 11)) {
                if (mode in excludedModes) parameters.append("exclMOT_$mode", "1")
            }
        }
    }

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

    // Departures + parking metrics
    private val departuresTimer = metrics.timer("nsw.departures.duration")
    private val departuresError = metrics.counter("nsw.departures.error")
    private val carparkTimer = metrics.timer("nsw.carpark.duration")
    private val carparkError = metrics.counter("nsw.carpark.error")

    // GTFS-RT metrics
    private val gtfsRtTimer = metrics.timer("nsw.gtfsrt.duration")
    private val gtfsRtError = metrics.counter("nsw.gtfsrt.error")

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
        // Self-imposed daily budget check (Sydney midnight reset).
        // Counted before the call attempt so that even failed calls consume budget — they
        // still hit NSW quota.
        if (!dailyBudget.tryAcquire()) {
            tripError.inc()
            throw NswBudgetExceededException()
        }

        val ctx = tripTimer.time()
        return try {
            val baseUrl = "${config.baseUrl.trimEnd('/')}/v1/tp/trip"

            // Full request/response diagnostics are DEBUG-only: at INFO they log
            // complete URLs, headers and 2 KB response bodies per call — log-volume
            // cost on a basic-xxs instance and needless retention of user journey
            // patterns in platform logs.
            logger.debug(LOG_SEPARATOR)
            logger.debug("🚀 NSW TRIP API REQUEST")
            logger.debug(LOG_SEPARATOR)
            logger.debug("📋 Input Parameters:")
            logger.debug("   Origin Stop ID: {}", originStopId)
            logger.debug("   Destination Stop ID: {}", destinationStopId)
            logger.debug("   DepArr Mode: {}", depArr)
            logger.debug("   Date: {}", date ?: "not set")
            logger.debug("   Time: {}", time ?: "not set")
            logger.debug("   Excluded Modes: {}", excludedModes)
            logger.debug("   Base URL: {}", baseUrl)

            val response = http.get(baseUrl) {
                // Add Authorization header for NSW Transport API
                headers.append("Authorization", "apikey ${config.apiKey}")
                url { appendTripPlanParams(originStopId, destinationStopId, depArr, date, time, excludedModes) }
            }

            // Log the actual request URL from the response
            logger.debug("📍 Complete Request URL: {}", response.call.request.url.toString())

            // Log request headers; Authorization fully redacted (public repo + public CI logs).
            if (logger.isDebugEnabled) {
                logger.debug("📤 REQUEST HEADERS:")
                response.call.request.headers.entries().forEach { entry ->
                    val displayValue = if (entry.key.equals("Authorization", ignoreCase = true)) {
                        "[REDACTED]"
                    } else {
                        entry.value.joinToString(", ")
                    }
                    logger.debug("   {} = {}", entry.key, displayValue)
                }
                logger.debug("   Method: {}", response.call.request.method.value)

                logger.debug(LOG_SEPARATOR)
                logger.debug("📥 NSW TRIP API RESPONSE")
                logger.debug(LOG_SEPARATOR)
                logger.debug("HTTP Status: {}", response.status.value)
                logger.debug("HTTP Status Description: {}", response.status.description)

                logger.debug("📦 RESPONSE HEADERS:")
                response.headers.entries().forEach { entry ->
                    logger.debug("   {} = {}", entry.key, entry.value.joinToString(", "))
                }
            }

            // Get raw response body as text for logging
            val responseText = response.bodyAsText()
            if (logger.isDebugEnabled) {
                logger.debug("📄 RAW RESPONSE BODY (length: {} bytes):", responseText.length)
                logger.debug(responseText.take(2000))
                if (responseText.length > 2000) {
                    logger.debug("... (truncated, showing first 2000 of {} chars)", responseText.length)
                }
                logger.debug(LOG_SEPARATOR)
            }

            // Check HTTP status code before parsing.
            // Counter is incremented in the outer catch block — don't double-count here.
            if (!response.status.isSuccess()) {
                logger.error("❌ NSW API returned error status: {} - {}", response.status.value, response.status.description)
                logger.error("Error response body: {}", responseText.take(500))
                throw NswUpstreamException(
                    statusCode = response.status.value,
                    message = "NSW API returned ${response.status.value} ${response.status.description}",
                    responseBody = responseText,
                )
            }

            // Parse using the companion-shared Json instance (see top of file).
            val tripResponse: TripResponse = NSW_JSON.decodeFromString(responseText)

            tripSuccess.inc()

            // Single concise INFO line per successful call — stop IDs only, no URLs/bodies.
            logger.info("NSW trip OK — origin={}, destination={}, journeys={}, hasError={}",
                originStopId, destinationStopId,
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

    override suspend fun getTripRaw(
        originStopId: String,
        destinationStopId: String,
        depArr: String,
        date: String?,
        time: String?,
        excludedModes: Set<Int>,
    ): String {
        // Self-imposed daily budget check — same accounting as getTrip().
        if (!dailyBudget.tryAcquire()) {
            tripError.inc()
            throw NswBudgetExceededException()
        }

        val ctx = tripTimer.time()
        return try {
            val baseUrl = "${config.baseUrl.trimEnd('/')}/v1/tp/trip"
            val response = http.get(baseUrl) {
                headers.append("Authorization", "apikey ${config.apiKey}")
                url { appendTripPlanParams(originStopId, destinationStopId, depArr, date, time, excludedModes) }
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                tripError.inc()
                logger.error("NSW trip API returned error status: {} - {}",
                    response.status.value, response.status.description)
                throw NswUpstreamException(
                    statusCode = response.status.value,
                    message = "NSW API returned ${response.status.value} ${response.status.description}",
                    responseBody = body,
                )
            }
            tripSuccess.inc()
            logger.debug("NSW trip raw OK — origin={}, destination={}, body={} bytes",
                originStopId, destinationStopId, body.length)
            body
        } catch (e: NswException) {
            throw e
        } catch (e: Throwable) {
            tripError.inc()
            logger.error("Failed to fetch trip raw from NSW — origin={}, destination={}",
                originStopId, destinationStopId, e)
            throw e
        } finally {
            ctx.stop()
        }
    }

    override suspend fun getTripProto(
        originStopId: String,
        destinationStopId: String,
        depArr: String,
        date: String?,
        time: String?,
        excludedModes: Set<Int>
    ): JourneyList {
        // Get JSON response
        val jsonResponse = getTrip(originStopId, destinationStopId, depArr, date, time, excludedModes)

        // Convert to proto and log
        val journeyList = JourneyListMapper.toProto(jsonResponse)

        // Per-journey dump is DEBUG-only — same log-volume rationale as the request
        // diagnostics in getTrip().
        if (logger.isDebugEnabled) {
            logger.debug(LOG_SEPARATOR)
            logger.debug("🚊 PROTOBUF JOURNEY LIST")
            logger.debug(LOG_SEPARATOR)
            logger.debug("Number of journeys: {}", journeyList.journeys.size)
            journeyList.journeys.forEachIndexed { index, journey ->
                logger.debug("\n--- Journey #{} ---", index + 1)
                logger.debug("  Time Text: {}", journey.time_text)
                logger.debug("  Origin Time: {} ({})", journey.origin_time, journey.origin_utc_date_time)
                logger.debug("  Destination Time: {} ({})", journey.destination_time, journey.destination_utc_date_time)
                logger.debug("  Travel Time: {}", journey.travel_time)
                journey.total_walk_time?.let { logger.debug("  Total Walk Time: {}", it) }
                journey.platform_text?.let { logger.debug("  Platform: {}", it) }
                logger.debug("  Transport Modes: {}", journey.transport_mode_lines.joinToString(", ") {
                    "${it.line_name} (type=${it.transport_mode_type})"
                })
                logger.debug("  Number of Legs: {}", journey.legs.size)
                logger.debug("  Service Alerts: {}", journey.total_unique_service_alerts)
                journey.departure_deviation?.let { deviation ->
                    val deviationText = when {
                        deviation.late != null -> "Late: ${deviation.late}"
                        deviation.early != null -> "Early: ${deviation.early}"
                        deviation.on_time == true -> "On Time"
                        else -> "Unknown"
                    }
                    logger.debug("  Departure Deviation: {}", deviationText)
                }
            }
            logger.debug(LOG_SEPARATOR)
        }

        // Convert to proto
        return journeyList
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

    override suspend fun getDeparturesRaw(stopId: String, date: String?, time: String?): String {
        if (!dailyBudget.tryAcquire()) {
            departuresError.inc()
            throw NswBudgetExceededException()
        }
        val ctx = departuresTimer.time()
        return try {
            val response = http.get("${config.baseUrl.trimEnd('/')}/v1/tp/departure_mon") {
                headers.append("Authorization", "apikey ${config.apiKey}")
                url {
                    parameters.append("name_dm", stopId)
                    parameters.append("type_dm", "any")
                    parameters.append("mode", "direct")
                    parameters.append("excludedMeans", "checkbox")
                    parameters.append("limit", "20")
                    date?.let { parameters.append("itdDate", it) }
                    time?.let { parameters.append("itdTime", it) }
                    parameters.append("outputFormat", "rapidJSON")
                    parameters.append("coordOutputFormat", "EPSG:4326")
                    parameters.append("TfNSWDM", "true")
                    parameters.append("version", "10.2.1.42")
                }
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                departuresError.inc()
                throw NswUpstreamException(
                    statusCode = response.status.value,
                    message = "NSW departure_mon returned ${response.status.value} ${response.status.description}",
                    responseBody = body,
                )
            }
            body
        } catch (e: NswException) {
            throw e
        } catch (e: Throwable) {
            departuresError.inc()
            logger.error("Failed to fetch departures for stop {}", stopId, e)
            throw e
        } finally {
            ctx.stop()
        }
    }

    override suspend fun getGtfsRealtimeRaw(version: Int, feed: String): ByteArray {
        return fetchGtfsRtBytes(path = "/v$version/gtfs/realtime/$feed", what = "realtime/$feed")
    }

    override suspend fun getVehiclePositionsRaw(feed: String, version: Int): ByteArray {
        return fetchGtfsRtBytes(path = "/v$version/gtfs/vehiclepos/$feed", what = "vehiclepos/$feed")
    }

    private suspend fun fetchGtfsRtBytes(path: String, what: String): ByteArray {
        if (!dailyBudget.tryAcquire()) {
            gtfsRtError.inc()
            throw NswBudgetExceededException()
        }
        val ctx = gtfsRtTimer.time()
        return try {
            val response: HttpResponse = http.get("${config.baseUrl.trimEnd('/')}$path") {
                headers.append("Authorization", "apikey ${config.apiKey}")
            }
            if (!response.status.isSuccess()) {
                gtfsRtError.inc()
                val body = response.bodyAsText()
                throw NswUpstreamException(
                    statusCode = response.status.value,
                    message = "NSW GTFS-RT $what returned ${response.status.value} ${response.status.description}",
                    responseBody = body,
                )
            }
            // Read raw protobuf bytes — never log content (binary, large).
            response.body<ByteArray>()
        } catch (e: NswException) {
            throw e
        } catch (e: Throwable) {
            gtfsRtError.inc()
            logger.error("Failed to fetch GTFS-RT {}", what, e)
            throw e
        } finally {
            ctx.stop()
        }
    }

    override suspend fun getCarparkRaw(facilityId: String?): String {
        if (!dailyBudget.tryAcquire()) {
            carparkError.inc()
            throw NswBudgetExceededException()
        }
        val ctx = carparkTimer.time()
        return try {
            val response = http.get("${config.baseUrl.trimEnd('/')}/v1/carpark") {
                headers.append("Authorization", "apikey ${config.apiKey}")
                url {
                    facilityId?.let { parameters.append("facility", it) }
                }
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                carparkError.inc()
                throw NswUpstreamException(
                    statusCode = response.status.value,
                    message = "NSW carpark returned ${response.status.value} ${response.status.description}",
                    responseBody = body,
                )
            }
            body
        } catch (e: NswException) {
            throw e
        } catch (e: Throwable) {
            carparkError.inc()
            logger.error("Failed to fetch carpark (facility={})", facilityId, e)
            throw e
        } finally {
            ctx.stop()
        }
    }
}
