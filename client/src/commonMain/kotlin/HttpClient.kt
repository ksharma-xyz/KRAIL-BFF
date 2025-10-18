package com.example.com

import io.ktor.client.*
import io.ktor.http.*
import io.opentelemetry.instrumentation.ktor.v3_0.KtorClientTelemetry

fun HttpClientConfig<*>.configureForProject() {
    val openTelemetry = getOpenTelemetry(serviceName = "opentelemetry-ktor-sample-client")
    
    install(KtorClientTelemetry) {
        setOpenTelemetry(openTelemetry)
    
        capturedRequestHeaders(HttpHeaders.Accept)
    
        attributesExtractor {
            onStart {
                attributes.put("start-time", System.currentTimeMillis())
            }
            onEnd {
                attributes.put("end-time", System.currentTimeMillis())
            }
        }
    }
}
