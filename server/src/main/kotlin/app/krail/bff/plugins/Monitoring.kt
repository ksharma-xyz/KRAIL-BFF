package app.krail.bff.plugins

import com.codahale.metrics.Slf4jReporter
import dev.hayden.KHealth
import io.ktor.server.application.*
import io.ktor.server.metrics.dropwizard.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*
import java.util.concurrent.TimeUnit
import org.slf4j.event.Level

fun Application.configureMonitoring() {
    // Health endpoint(s)
    install(KHealth)

    // Basic metrics with Dropwizard
    install(DropwizardMetrics) {
        Slf4jReporter.forRegistry(registry)
            .outputTo(this@configureMonitoring.log)
            .convertRatesTo(TimeUnit.SECONDS)
            .convertDurationsTo(TimeUnit.MILLISECONDS)
            .build()
            .start(10, TimeUnit.SECONDS)
    }

    // Request logging
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/") }
    }
}
