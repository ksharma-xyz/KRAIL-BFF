val kotlin_version: String by project
val ktor_version: String by project
val logback_version: String by project

plugins {
    kotlin("multiplatform") version "2.2.20"
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            api("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure:1.52.0")
            api("io.opentelemetry.semconv:opentelemetry-semconv:1.34.0")
            api("io.opentelemetry:opentelemetry-exporter-otlp:1.52.0")
            api("io.opentelemetry.instrumentation:opentelemetry-ktor-3.0:2.18.0-alpha")
        }
    }
}
