package app.krail.bff.di

import app.krail.bff.client.nsw.NswClient
import app.krail.bff.client.nsw.NswClientImpl
import app.krail.bff.config.NswConfig
import com.codahale.metrics.MetricRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
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
    val retryMaxAttempts = config.propertyOrNull("nsw.retryMaxAttempts")?.getString()?.toIntOrNull()
        ?: System.getenv("NSW_RETRY_MAX_ATTEMPTS")?.toIntOrNull()
        ?: 2
    val retryBackoffMs = config.propertyOrNull("nsw.retryBackoffMs")?.getString()?.toLongOrNull()
        ?: System.getenv("NSW_RETRY_BACKOFF_MS")?.toLongOrNull()
        ?: 200L
    val breakerThreshold = config.propertyOrNull("nsw.breakerFailureThreshold")?.getString()?.toIntOrNull()
        ?: System.getenv("NSW_BREAKER_FAILURE_THRESHOLD")?.toIntOrNull()
        ?: 5
    val breakerResetMs = config.propertyOrNull("nsw.breakerResetTimeoutMs")?.getString()?.toLongOrNull()
        ?: System.getenv("NSW_BREAKER_RESET_TIMEOUT_MS")?.toLongOrNull()
        ?: 30_000L
    return NswConfig(
        baseUrl = baseUrl,
        apiKey = apiKey,
        connectTimeoutMs = connectTimeout,
        readTimeoutMs = readTimeout,
        retryMaxAttempts = retryMaxAttempts,
        retryBackoffMs = retryBackoffMs,
        breakerFailureThreshold = breakerThreshold,
        breakerResetTimeoutMs = breakerResetMs
    )
}

private fun configModule(appConfig: ApplicationConfig) = module {
    single<ApplicationConfig> { appConfig }
    single { provideNswConfig(appConfig) }
    // Shared metrics registry for custom app/client metrics
    single { MetricRegistry() }
}

private fun httpClientModule() = module {
    single<HttpClient> {
        val cfg: NswConfig = get()
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false })
            }
            install(HttpTimeout) {
                connectTimeoutMillis = cfg.connectTimeoutMs
                requestTimeoutMillis = cfg.readTimeoutMs
                socketTimeoutMillis = cfg.readTimeoutMs
            }
            install(HttpRequestRetry) {
                retryOnException(maxRetries = cfg.retryMaxAttempts) // total attempts = 1 + maxRetries
                exponentialDelay(base = cfg.retryBackoffMs.toDouble())
                retryIf { _, response ->
                    // Retry on 5xx, but not on 4xx
                    response.status.value in 500..599
                }
            }
            defaultRequest {
                // NSW Transport API uses Authorization: apikey <key>
                val key = cfg.apiKey
                if (key.isNotBlank()) {
                    headers.append("Authorization", "apikey $key")
                }
            }
        }
    }
}

private fun clientModule() = module {
    // Bind NswClient as a singleton using the provided HttpClient and NswConfig
    single<NswClient> { NswClientImpl(get<HttpClient>(), get<NswConfig>(), get<MetricRegistry>()) }
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
