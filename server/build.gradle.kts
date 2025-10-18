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

    // Ktor HTTP client for upstream calls
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
}
