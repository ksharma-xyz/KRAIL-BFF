package app.krail.bff.config

data class NswConfig(
    val baseUrl: String,
    val apiKey: String,
    val connectTimeoutMs: Long = 5_000,
    val readTimeoutMs: Long = 5_000
)

