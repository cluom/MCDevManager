package com.lemon.mcdevmanagermp.data.repository

import com.lemon.mcdevmanagermp.domain.update.AltStoreUpdate
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders

class AltStoreSourceRepository {
    suspend fun findUpdate(currentVersion: String, requiredVersion: String): AltStoreUpdate.Package? {
        // Deliberately isolated from the authenticated NetEase API client and its cookies.
        val client = HttpClient {
            expectSuccess = true
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 15_000
            }
        }
        return try {
            val source = client.get(AltStoreUpdate.SOURCE_URL) {
                header(HttpHeaders.CacheControl, "no-cache")
            }.bodyAsText()
            AltStoreUpdate.select(source, currentVersion, requiredVersion)
        } finally {
            client.close()
        }
    }
}
