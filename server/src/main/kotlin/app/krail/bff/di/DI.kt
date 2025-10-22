package app.krail.bff.di

import app.krail.bff.client.nsw.NswClient
import app.krail.bff.client.nsw.NswClientImpl
import app.krail.bff.config.NswConfig
import com.codahale.metrics.MetricRegistry
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
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Properties

private val logger = LoggerFactory.getLogger("app.krail.bff.di.DI")

private fun loadLocalProperties(): Properties {
    val props = Properties()
    val localPropertiesFile = File("local.properties")
    if (localPropertiesFile.exists()) {
        try {
            localPropertiesFile.inputStream().use { props.load(it) }
            logger.info("✅ Loaded local.properties file from: {}", localPropertiesFile.absolutePath)
        } catch (e: Exception) {
            logger.warn("⚠️  Failed to load local.properties: {}", e.message)
        }
    } else {
        logger.debug("ℹ️  No local.properties file found (this is expected on servers)")
    }
    return props
}

private fun provideNswConfig(config: ApplicationConfig): NswConfig {
    val localProps = loadLocalProperties()

    // Priority: 1. Environment variable (for servers), 2. local.properties (for local dev), 3. application.yaml
    val baseUrl = System.getenv("NSW_BASE_URL")
        ?: localProps.getProperty("nsw.baseUrl")
        ?: config.propertyOrNull("nsw.baseUrl")?.getString()
        ?: "https://api.transport.nsw.gov.au"

    val apiKey = System.getenv("NSW_API_KEY")
        ?: localProps.getProperty("nsw.apiKey")
        ?: config.propertyOrNull("nsw.apiKey")?.getString()?.takeIf { it.isNotBlank() }
        ?: ""

    // Validate API key is not empty
    if (apiKey.isBlank()) {
        val hasLocalProperties = File("local.properties").exists()
        val errorMessage = if (hasLocalProperties) {
            // Running locally with local.properties file
            """
            ╔═══════════════════════════════════════════════════════���═══════════════════╗
            ║                                                                           ║
            ║  ❌ CONFIGURATION ERROR: NSW Transport API Key is missing!                ║
            ║                                                                           ║
            ║  The application requires a valid NSW Transport API key to function.     ║
            ║                                                                           ║
            ║  You have a local.properties file, but the API key is not set.           ║
            ║                                                                           ║
            ║  Please add to local.properties file:                                    ║
            ║    nsw.apiKey=your-api-key-here                                          ║
            ║                                                                           ║
            ║  📝 Get your API key from:                                                ║
            ║     https://opendata.transport.nsw.gov.au/                                ║
            ║                                                                           ║
            ╚═══════════════════════���═══════════════════════════════════════════════════╝
            """.trimIndent()
        } else {
            // Running on server without local.properties
            """
            ╔═══════════════════════���═══════════════════════════════════════���═══════════╗
            ║                                                                           ║
            ║  ❌ CONFIGURATION ERROR: NSW Transport API Key is missing!                ║
            ║                                                                           ║
            ║  The application requires a valid NSW Transport API key to function.     ║
            ║                                                                           ║
            ║  Running on server (no local.properties file detected).                  ║
            ║                                                                           ║
            ║  Please set the NSW_API_KEY environment variable:                        ║
            ║    export NSW_API_KEY=your-api-key-here                                  ║
            ║                                                                           ║
            ║  Or update server/src/main/resources/application.yaml:                   ║
            ║    nsw:                                                                   ║
            ║      apiKey: "your-api-key-here"                                         ║
            ║                                                                           ║
            ║  📝 For local development, create a local.properties file:                ║
            ║     cp local.properties.template local.properties                        ║
            ║     # Then add: nsw.apiKey=your-api-key-here                             ║
            ║                                                                           ║
            ╚═══════════════════════���═══════════════════════════════════════���═══════════╝
            """.trimIndent()
        }
        logger.error(errorMessage)
        throw IllegalStateException("NSW API Key is required but not configured. See logs above for configuration instructions.")
    }

    // Log configuration source for debugging
    val configSource = when {
        System.getenv("NSW_API_KEY") != null -> "environment variable"
        localProps.getProperty("nsw.apiKey") != null -> "local.properties file"
        else -> "application.yaml"
    }
    logger.info("✅ NSW API Key loaded successfully from: {}", configSource)
    logger.info("   API Key length: {} chars", apiKey.length)
    logger.info("   Base URL: {}", baseUrl)

    val connectTimeout = System.getenv("NSW_CONNECT_TIMEOUT_MS")?.toLongOrNull()
        ?: localProps.getProperty("nsw.connectTimeoutMs")?.toLongOrNull()
        ?: config.propertyOrNull("nsw.connectTimeoutMs")?.getString()?.toLongOrNull()
        ?: 10_000L
    val readTimeout = System.getenv("NSW_READ_TIMEOUT_MS")?.toLongOrNull()
        ?: localProps.getProperty("nsw.readTimeoutMs")?.toLongOrNull()
        ?: config.propertyOrNull("nsw.readTimeoutMs")?.getString()?.toLongOrNull()
        ?: 10_000L
    val breakerFailureThreshold = System.getenv("NSW_BREAKER_FAILURE_THRESHOLD")?.toIntOrNull()
        ?: localProps.getProperty("nsw.breakerFailureThreshold")?.toIntOrNull()
        ?: config.propertyOrNull("nsw.breakerFailureThreshold")?.getString()?.toIntOrNull()
        ?: 3
    val breakerResetTimeoutMs = System.getenv("NSW_BREAKER_RESET_TIMEOUT_MS")?.toLongOrNull()
        ?: localProps.getProperty("nsw.breakerResetTimeoutMs")?.toLongOrNull()
        ?: config.propertyOrNull("nsw.breakerResetTimeoutMs")?.getString()?.toLongOrNull()
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
