import java.util.Properties

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "KRAIL-BFF"

// Read local.properties for dev credentials (git-ignored, never committed).
val localProps = Properties().apply {
    rootProject.projectDir.resolve("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://packages.confluent.io/maven/")
        // KRAIL-API-PROTO proto sources JAR — published on each proto release tag.
        // CI: GITHUB_TOKEN env var is set automatically (has read:packages scope).
        // Local dev: add gpr.token=<github-pat-with-read:packages> to local.properties.
        //   See local.properties.template for instructions.
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/ksharma-xyz/KRAIL-API-PROTO")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                    ?: localProps.getProperty("gpr.user") ?: "token"
                password = System.getenv("GITHUB_TOKEN")
                    ?: localProps.getProperty("gpr.token") ?: ""
            }
        }
    }
}

include(":server")
