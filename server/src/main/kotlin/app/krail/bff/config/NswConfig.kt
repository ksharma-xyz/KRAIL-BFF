package app.krail.bff.config

data class NswConfig(
    val baseUrl: String,
    val apiKey: String,
    val connectTimeoutMs: Long = 5_000,
    val readTimeoutMs: Long = 5_000,
    val retryMaxAttempts: Int = 2,
    val retryBackoffMs: Long = 200,
    val breakerFailureThreshold: Int = 5,
    val breakerResetTimeoutMs: Long = 30_000
)
