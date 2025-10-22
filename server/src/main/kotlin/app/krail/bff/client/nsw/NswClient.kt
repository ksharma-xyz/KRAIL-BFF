package app.krail.bff.client.nsw

import app.krail.bff.config.NswConfig
import com.codahale.metrics.MetricRegistry
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

interface NswClient {
    suspend fun healthCheck(): Boolean
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
                // reset breaker on success
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
