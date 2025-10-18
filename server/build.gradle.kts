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
    // Configure Netty to avoid Unsafe and native transport usage (reduces perf but avoids restricted calls)
    applicationDefaultJvmArgs = listOf(
        "-Dio.netty.noUnsafe=true",
        "-Dio.netty.transport.noNative=true"
    )
}

// Forward only an explicit project property (-PrunPort=NNNN) to the app run task
// This avoids reading environment variables in the build and keeps runs reproducible.
tasks.named<JavaExec>("run") {
    project.findProperty("runPort")?.toString()?.takeIf { it.isNotBlank() }?.let {
        systemProperty("ktor.deployment.port", it)
    }
    // Force the :server:run task to use the configured toolchain (JDK 17 by default)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(jdkVersion)) })
    jvmArgs = (jvmArgs ?: emptyList()) + listOf(
        "-Dio.netty.noUnsafe=true",
        "-Dio.netty.transport.noNative=true"
    )
}

dependencies {
    implementation(libs.khealth)

    implementation(libs.ktor.server.metrics)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)
    implementation(libs.ktor.server.config.yaml)

    // DI
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)

    // Ktor HTTP client for upstream calls
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
}
