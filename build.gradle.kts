plugins {
    kotlin("jvm") version "2.2.20" apply false
    kotlin("multiplatform") version "2.2.20" apply false
    id("io.ktor.plugin") version "3.3.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20" apply false
}

subprojects {
    group = "com.example.com"
    version = "0.0.1"
}
