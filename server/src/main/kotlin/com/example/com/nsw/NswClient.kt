package com.example.com.nsw

import io.ktor.client.HttpClient
import io.ktor.client.request.get

interface NswClient {
    suspend fun healthCheck(): Boolean
}

class NswClientImpl(
    private val http: HttpClient,
    private val config: com.example.com.NswConfig
) : NswClient {
    override suspend fun healthCheck(): Boolean {
        // Minimal placeholder: attempt GET on base URL root or simple endpoint
        // In real implementation, target a lightweight NSW endpoint.
        val url = config.baseUrl.trimEnd('/') + "/"
        return try {
            http.get(url)
            true
        } catch (_: Throwable) {
            false
        }
    }
}

