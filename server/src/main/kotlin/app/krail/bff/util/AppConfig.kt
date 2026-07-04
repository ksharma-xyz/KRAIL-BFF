package app.krail.bff.util

import io.ktor.server.config.ApplicationConfig

/**
 * The BFF-wide config cascade: environment variable > application.yaml > default.
 *
 * Production sets env vars on the platform; application.yaml carries dev/test
 * defaults. (local.properties is forwarded to env vars by the :server:run task,
 * so it participates via the env layer — see server/build.gradle.kts.)
 *
 * Blank values are treated as unset at both layers so an empty env var can't
 * shadow a configured yaml value by accident.
 */
fun ApplicationConfig.stringOrNull(env: String, path: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: propertyOrNull(path)?.getString()?.takeIf { it.isNotBlank() }

fun ApplicationConfig.string(env: String, path: String, default: String): String =
    stringOrNull(env, path) ?: default

fun ApplicationConfig.long(env: String, path: String, default: Long): Long =
    stringOrNull(env, path)?.toLongOrNull() ?: default

fun ApplicationConfig.int(env: String, path: String, default: Int): Int =
    stringOrNull(env, path)?.toIntOrNull() ?: default

fun ApplicationConfig.boolean(env: String, path: String, default: Boolean): Boolean =
    stringOrNull(env, path)?.equals("true", ignoreCase = true) ?: default
