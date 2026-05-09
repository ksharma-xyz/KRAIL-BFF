import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.wire)
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

    // Load local.properties (dev-only) and forward known keys as env vars,
    // because the BFF reads its config from env > yaml at runtime — production
    // sets env vars on the platform; dev mirrors that here.
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        val localProperties = Properties()
        localProperties.load(localPropertiesFile.inputStream())

        // Property name in local.properties → env var name expected by the app.
        // Add new entries here when the app gains a new config knob; keeping the
        // map explicit avoids surprise leaks (e.g. accidental forwarding of arbitrary
        // local.properties entries into the JVM environment).
        val propToEnv = linkedMapOf(
            // NSW upstream
            "nsw.apiKey" to "NSW_API_KEY",
            "nsw.baseUrl" to "NSW_BASE_URL",
            "nsw.connectTimeoutMs" to "NSW_CONNECT_TIMEOUT_MS",
            "nsw.readTimeoutMs" to "NSW_READ_TIMEOUT_MS",
            "nsw.breakerFailureThreshold" to "NSW_BREAKER_FAILURE_THRESHOLD",
            "nsw.breakerResetTimeoutMs" to "NSW_BREAKER_RESET_TIMEOUT_MS",
            "nsw.dailyBudget" to "NSW_DAILY_BUDGET",
            // BFF gates / CORS / rate limit
            "bff.cors.origins" to "BFF_CORS_ORIGINS",
            "bff.minAppVersion" to "MIN_APP_VERSION",
            "bff.cfOriginToken" to "CF_ORIGIN_TOKEN",
            "bff.rateLimit.rps" to "BFF_RATE_LIMIT_RPS",
            "bff.rateLimit.burst" to "BFF_RATE_LIMIT_BURST",
            "bff.perIp.rps" to "BFF_PER_IP_RPS",
            "bff.perIp.burst" to "BFF_PER_IP_BURST",
            "bff.perIp.maxIps" to "BFF_PER_IP_MAX",
            // Dev-only API-tester passthrough (NEVER set in production)
            "bff.devPassthrough" to "BFF_DEV_PASSTHROUGH",
            // Static data manifests
            "data.stops.manifestUrl" to "STOPS_MANIFEST_URL",
            "data.routes.manifestUrl" to "ROUTES_MANIFEST_URL",
        )
        propToEnv.forEach { (prop, envName) ->
            localProperties.getProperty(prop)?.takeIf { it.isNotBlank() }?.let { value ->
                environment(envName, value)
            }
        }
    }

    // Force the :server:run task to use the configured toolchain (JDK 17 by default)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(jdkVersion)) })
}

// Build the stops dataset (.pb + manifest.json) from one or more GTFS zip bundles.
// Used by the .github/workflows/datasets.yml cron + workflow_dispatch.
// Args (positional): <gtfsDir> <output.pb> <version> <releaseUrl>
tasks.register<JavaExec>("buildStopsDataset") {
    group = "data"
    description = "Build stops dataset .pb + manifest.json from GTFS zip bundles"
    mainClass.set("app.krail.bff.tools.BuildStopsDatasetKt")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf(
        project.findProperty("gtfsDir")?.toString() ?: "${layout.buildDirectory.get().asFile}/gtfs",
        project.findProperty("output")?.toString() ?: "${layout.buildDirectory.get().asFile}/dist/stops.pb",
        project.findProperty("version")?.toString() ?: "dev",
        project.findProperty("releaseUrl")?.toString() ?: "https://example.invalid/stops.pb",
    )
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(jdkVersion)) })
}

// Build the routes dataset (.pb + routes-manifest.json) from one or more GTFS zip bundles.
// Used by the .github/workflows/datasets.yml cron + workflow_dispatch.
tasks.register<JavaExec>("buildRoutesDataset") {
    group = "data"
    description = "Build routes dataset .pb + manifest.json from GTFS zip bundles"
    mainClass.set("app.krail.bff.tools.BuildRoutesDatasetKt")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf(
        project.findProperty("gtfsDir")?.toString() ?: "${layout.buildDirectory.get().asFile}/gtfs",
        project.findProperty("output")?.toString() ?: "${layout.buildDirectory.get().asFile}/dist/routes.pb",
        project.findProperty("version")?.toString() ?: "dev",
        project.findProperty("releaseUrl")?.toString() ?: "https://example.invalid/routes.pb",
    )
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(jdkVersion)) })
}

// Wire configuration for Protocol Buffers
wire {
    kotlin {
        // Generate Kotlin code
        javaInterop = true
        // Use explicit nullability for optional fields
        emitAppliedOptions = true
    }

    sourcePath {
        srcDir("src/main/proto")
    }
}

// Netty native transport versions; align with Ktor's Netty (4.2.x)
val nettyVersion = "4.2.12.Final"

dependencies {
    implementation(libs.khealth)

    implementation(libs.ktor.server.metrics)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.compression)
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

    // Wire / Protobuf
    implementation(libs.wire.runtime)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.ktor.client.mock)
}
