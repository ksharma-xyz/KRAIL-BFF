package com.example.com

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.config.ApplicationConfig
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

// Basic NSW client config model
data class NswConfig(
    val baseUrl: String,
    val apiKey: String,
    val connectTimeoutMs: Long = 5_000,
    val readTimeoutMs: Long = 5_000
)

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
    return NswConfig(baseUrl = baseUrl, apiKey = apiKey, connectTimeoutMs = connectTimeout, readTimeoutMs = readTimeout)
}

private fun configModule(appConfig: ApplicationConfig) = module {
    single<ApplicationConfig> { appConfig }
    single { provideNswConfig(appConfig) }
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
        }
    }
}

private fun clientModule() = module {
    // Bind NswClient as a singleton using the provided HttpClient and NswConfig
    single<com.example.com.nsw.NswClient> { com.example.com.nsw.NswClientImpl(get(), get()) }
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
