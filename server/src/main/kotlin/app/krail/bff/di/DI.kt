package app.krail.bff.di

import app.krail.bff.client.nsw.NswClient
import app.krail.bff.client.nsw.NswClientImpl
import app.krail.bff.config.NswConfig
import com.codahale.metrics.MetricRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.config.ApplicationConfig
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

private fun provideNswConfig(config: ApplicationConfig): NswConfig {
    val baseUrl = config.propertyOrNull("nsw.baseUrl")?.getString()
        ?: System.getenv("NSW_BASE_URL")
        ?: "https://api.transport.nsw.gov.au"
    val apiKey = config.propertyOrNull("nsw.apiKey")?.getString()
        ?: System.getenv("NSW_API_KEY")
        ?: ""
    val connectTimeout = config.propertyOrNull("nsw.connectTimeoutMs")?.getString()?.toLongOrNull()
        ?: System.getenv("NSW_CONNECT_TIMEOUT_MS")?.toLongOrNull()
        ?: 5_000L
    val readTimeout = config.propertyOrNull("nsw.readTimeoutMs")?.getString()?.toLongOrNull()
        ?: System.getenv("NSW_READ_TIMEOUT_MS")?.toLongOrNull()
        ?: 5_000L
    val breakerFailureThreshold = config.propertyOrNull("nsw.breakerFailureThreshold")?.getString()?.toIntOrNull()
        ?: System.getenv("NSW_BREAKER_FAILURE_THRESHOLD")?.toIntOrNull()
        ?: 3
    val breakerResetTimeoutMs = config.propertyOrNull("nsw.breakerResetTimeoutMs")?.getString()?.toLongOrNull()
        ?: System.getenv("NSW_BREAKER_RESET_TIMEOUT_MS")?.toLongOrNull()
        ?: 60_000L

    return NswConfig(
        baseUrl = baseUrl,
        apiKey = apiKey,
        connectTimeoutMs = connectTimeout,
        readTimeoutMs = readTimeout,
        breakerFailureThreshold = breakerFailureThreshold,
        breakerResetTimeoutMs = breakerResetTimeoutMs
    )
}

private fun configModule(appConfig: ApplicationConfig) = module {
    single<ApplicationConfig> { appConfig }
    single { provideNswConfig(appConfig) }
    single { MetricRegistry() }
}

private fun httpClientModule() = module {
    single<HttpClient> {
        val cfg: NswConfig = get()
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                    isLenient = true
                })
            }
            install(HttpTimeout) {
                connectTimeoutMillis = cfg.connectTimeoutMs
                requestTimeoutMillis = cfg.readTimeoutMs
                socketTimeoutMillis = cfg.readTimeoutMs
            }
            defaultRequest {
                headers.append(HttpHeaders.Authorization, "apikey ${cfg.apiKey}")
            }
        }
    }
}

private fun clientModule() = module {
    single<NswClient> { NswClientImpl(get(), get(), get()) }
}

fun Application.configureDI() {
    val appConfig = environment.config
    install(Koin) {
        slf4jLogger()
        modules(
            configModule(appConfig),
            httpClientModule(),
            clientModule()
        )
    }
}
