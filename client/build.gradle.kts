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
            api(project(":core"))
            api("io.opentelemetry.instrumentation:opentelemetry-ktor-3.0:2.18.0-alpha")
            api("io.ktor:ktor-client-core:$ktor_version")
        }
    }
}
