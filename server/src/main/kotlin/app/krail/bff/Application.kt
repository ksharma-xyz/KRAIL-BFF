package app.krail.bff

import app.krail.bff.di.configureDI
import app.krail.bff.plugins.configureHTTP
import app.krail.bff.plugins.configureMonitoring
import app.krail.bff.plugins.configureSerialization
import app.krail.bff.plugins.configureCorrelation
import app.krail.bff.plugins.configureErrorHandling
import app.krail.bff.plugins.configureMobileAnalytics
import app.krail.bff.routes.configureAdministration
import app.krail.bff.routes.configureRouting
import app.krail.bff.routes.configureTripRoutes
import io.ktor.server.application.*
import io.ktor.server.netty.*

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    configureDI()
    configureCorrelation()
    configureMobileAnalytics()
    configureErrorHandling()
    configureMonitoring()
    configureSerialization()
    configureAdministration()
    configureHTTP()
    configureRouting()
    configureTripRoutes()
}
