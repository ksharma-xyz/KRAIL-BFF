package app.krail.bff

import app.krail.bff.di.configureDI
import app.krail.bff.plugins.configureCompression
import app.krail.bff.plugins.configureCorrelation
import app.krail.bff.plugins.configureErrorHandling
import app.krail.bff.plugins.configureHTTP
import app.krail.bff.plugins.configureMobileAnalytics
import app.krail.bff.plugins.configureMonitoring
import app.krail.bff.plugins.configureOriginTokenGate
import app.krail.bff.plugins.configurePerIpRateLimit
import app.krail.bff.plugins.configureSerialization
import app.krail.bff.plugins.configureVersionGate
import app.krail.bff.routes.configureAdministration
import app.krail.bff.routes.configureDataRoutes
import app.krail.bff.routes.configureDepartureRoutes
import app.krail.bff.routes.configureGtfsRoutes
import app.krail.bff.routes.configureInternalRoutes
import app.krail.bff.routes.configureParkingRoutes
import app.krail.bff.routes.configureRouting
import app.krail.bff.routes.configureTrackRoutes
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
    configureCompression()
    configureSerialization()
    configureAdministration()
    configureOriginTokenGate()
    configureVersionGate()
    configurePerIpRateLimit()
    configureHTTP()
    configureRouting()
    configureTripRoutes()
    configureDepartureRoutes()
    configureParkingRoutes()
    configureGtfsRoutes()
    configureTrackRoutes()
    configureDataRoutes()
    configureInternalRoutes()
}
