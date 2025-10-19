package app.krail.bff.client.nsw

import app.krail.bff.config.NswConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get

interface NswClient {
    suspend fun healthCheck(): Boolean
}

class NswClientImpl(
    private val http: HttpClient,
    private val config: NswConfig
) : NswClient {
    override suspend fun healthCheck(): Boolean {
        val url = config.baseUrl.trimEnd('/') + "/"
        return try {
            http.get(url)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
