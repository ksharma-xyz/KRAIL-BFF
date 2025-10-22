import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
}

// Use Java toolchain version from version catalog
val jdkVersion = libs.versions.jdk.get().toInt()

kotlin {
    jvmToolchain(jdkVersion)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(jdkVersion))
    }
}

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

// Forward only an explicit project property (-PrunPort=NNNN) to the app run task
// This avoids reading environment variables in the build and keeps runs reproducible.
tasks.named<JavaExec>("run") {
    project.findProperty("runPort")?.toString()?.takeIf { it.isNotBlank() }?.let {
        systemProperty("ktor.deployment.port", it)
    }

    // Load local.properties if it exists
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        val localProperties = Properties()
        localProperties.load(localPropertiesFile.inputStream())

        // Pass NSW API key from local.properties to the application
        localProperties.getProperty("nsw.apiKey")?.let { key ->
            environment("NSW_API_KEY", key)
        }
        localProperties.getProperty("nsw.baseUrl")?.let { url ->
            environment("NSW_BASE_URL", url)
        }
        localProperties.getProperty("nsw.connectTimeoutMs")?.let { timeout ->
            environment("NSW_CONNECT_TIMEOUT_MS", timeout)
        }
        localProperties.getProperty("nsw.readTimeoutMs")?.let { timeout ->
            environment("NSW_READ_TIMEOUT_MS", timeout)
        }
    }

    // Force the :server:run task to use the configured toolchain (JDK 17 by default)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(jdkVersion)) })
}

// Netty native transport versions; align with Ktor's Netty (4.2.x)
val nettyVersion = "4.2.7.Final"

dependencies {
    implementation(libs.khealth)

    implementation(libs.ktor.server.metrics)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.logstash.logback.encoder)

    // Enable Netty native transports when available (Linux/macOS)
    runtimeOnly("io.netty:netty-transport-native-epoll:$nettyVersion:linux-x86_64")
    runtimeOnly("io.netty:netty-transport-native-epoll:$nettyVersion:linux-aarch_64")
    runtimeOnly("io.netty:netty-transport-native-kqueue:$nettyVersion:osx-x86_64")
    runtimeOnly("io.netty:netty-transport-native-kqueue:$nettyVersion:osx-aarch_64")

    // DI
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)

    // Ktor HTTP client for upstream calls
    implementation(libs.ktor.client.core)
    // Switched from CIO to OkHttp engine
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.ktor.client.mock)
}
