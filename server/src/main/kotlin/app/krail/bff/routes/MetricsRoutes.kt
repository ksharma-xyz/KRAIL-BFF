package app.krail.bff.routes

import app.krail.bff.client.nsw.NswDailyBudget
import app.krail.bff.util.boolean
import com.codahale.metrics.MetricRegistry
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("app.krail.bff.routes.MetricsRoutes")

/**
 * GET /internal/metrics — JSON snapshot of the in-process MetricRegistry plus
 * the NSW daily-budget position. Read-only, no cardinality explosion (fixed
 * metric names), intended for the synthetic-monitor workflow to scrape.
 *
 * Opt-in via BFF_METRICS_ENABLED / bff.metricsEnabled (default off). Not in
 * EXEMPT_PATHS, so in production it additionally sits behind the Cloudflare
 * origin-token gate like every other route.
 */
fun Application.configureMetricsRoutes() {
    val enabled = environment.config.boolean("BFF_METRICS_ENABLED", "bff.metricsEnabled", false)
    if (!enabled) {
        logger.debug("/internal/metrics disabled (BFF_METRICS_ENABLED unset)")
        return
    }
    logger.info("/internal/metrics enabled")

    val metrics by inject<MetricRegistry>()
    val budget by inject<NswDailyBudget>()

    routing {
        get("/internal/metrics") {
            val body = buildJsonObject {
                putJsonObject("nswBudget") {
                    put("limit", budget.limit)
                    put("used", budget.currentCount())
                    put("day", budget.currentDay().toString())
                }
                putJsonObject("counters") {
                    metrics.counters.forEach { (name, counter) -> put(name, counter.count) }
                }
                putJsonObject("timers") {
                    metrics.timers.forEach { (name, timer) ->
                        putJsonObject(name) {
                            val snap = timer.snapshot
                            put("count", timer.count)
                            put("meanMs", snap.mean / 1_000_000.0)
                            put("p95Ms", snap.get95thPercentile() / 1_000_000.0)
                            put("maxMs", snap.max / 1_000_000.0)
                        }
                    }
                }
            }
            call.respondText(body.toString(), ContentType.Application.Json)
        }
    }
}
