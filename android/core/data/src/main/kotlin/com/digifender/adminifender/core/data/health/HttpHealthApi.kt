package com.digifender.adminifender.core.data.health

import com.digifender.adminifender.core.data.network.AccessTokens
import com.digifender.adminifender.core.data.network.ReadResult
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Only fixed, typed diagnostic fields leave this adapter. Never logs bodies, errors or bearer tokens. */
class HttpHealthApi(client: OkHttpClient, private val baseUrl: HttpUrl, private val tokens: AccessTokens) : HealthApi {
    private val client = client.newBuilder().cache(null).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).callTimeout(30, TimeUnit.SECONDS).build()

    override suspend fun health(): ReadResult<HealthReport> = read("health/private", HealthWire::health)
    override suspend fun diagnostics(): ReadResult<DiagnosticReport> = read("diagnostics", HealthWire::diagnostics)

    private suspend fun <T> read(path: String, decode: (String) -> T): ReadResult<T> {
        val token = tokens.current() ?: return ReadResult.SessionExpired
        return try {
            withContext(Dispatchers.IO) {
                val url = baseUrl.newBuilder().addPathSegments("api/v1").addPathSegments(path).build()
                val request = Request.Builder().url(url).header("authorization", "Bearer $token").header("cache-control", "no-store").get().build()
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 401 -> { tokens.rejected(token); ReadResult.SessionExpired }
                        !response.isSuccessful -> ReadResult.Unavailable("health_unavailable")
                        // Operational metadata must fit in 1 MiB; source/archive response limits are deliberately larger.
                        response.body.contentLength() > MAX_HEALTH_BYTES || response.body.source().request(MAX_HEALTH_BYTES + 1) -> ReadResult.Unavailable("health_unavailable")
                        else -> ReadResult.Ok(decode(response.body.source().readUtf8()))
                    }
                }
            }
        } catch (_: IOException) {
            ReadResult.Unavailable("health_unavailable")
        } catch (_: IllegalArgumentException) {
            ReadResult.Unavailable("health_unavailable")
        }
    }

    private companion object { const val MAX_HEALTH_BYTES = 1024L * 1024 }
}
