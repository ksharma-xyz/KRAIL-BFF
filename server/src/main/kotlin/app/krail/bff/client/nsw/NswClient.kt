package app.krail.bff.client.nsw

import app.krail.bff.config.NswConfig
import app.krail.bff.model.TripResponse
import com.codahale.metrics.MetricRegistry
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
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
    http: HttpClient,
    config: NswConfig,
    metrics: MetricRegistry
) : NswClient {
    private val http: HttpClient = http
    private val config: NswConfig = config

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
            return false
        }

        val ctx = timer.time()
        return try {
            val url = config.baseUrl.trimEnd('/') + "/"
            val response: HttpResponse = http.get(url)
            val ok = response.status.isSuccess()
            if (ok) {
                failures.set(0)
                countSuccess.inc()
                true
            } else {
                // count failure and maybe trip breaker
                onFailure(response.status.value)
                false
            }
        } catch (_: Throwable) {
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
            val response = http.get("${config.baseUrl.trimEnd('/')}/v1/tp/trip") {
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
            tripSuccess.inc()
            response.body()
        } catch (e: Throwable) {
            tripError.inc()
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
        }
    }
}
