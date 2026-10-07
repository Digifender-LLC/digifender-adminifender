package com.digifender.adminifender.core.data.devicework

import com.digifender.adminifender.core.data.network.AccessTokens
import com.digifender.adminifender.core.data.network.Envelope
import com.digifender.adminifender.core.data.network.ErrorEnvelope
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Exact slice-11 protocol. Extraction is not supported by these backend endpoints yet. */
class HttpDeviceWorkApi(client: OkHttpClient, private val baseUrl: HttpUrl, private val tokens: AccessTokens) : DeviceWorkApi {
    // A redirected mutation could forward model/source text to another origin. Only the configured API owns it.
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    // Required nullable manifest fields must be present: the server's strict validator rejects omission.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override suspend fun readiness(body: ReadinessRequest, key: String) = post("readiness", body, ReadinessRequest.serializer(), ReadinessResponse.serializer(), key)
    override suspend fun claim(body: ReadinessRequest, key: String) = post("claim", body, ReadinessRequest.serializer(), ClaimResponse.serializer(), key)
    override suspend fun heartbeat(attemptId: String, body: HeartbeatRequest, key: String): DeviceCall<HeartbeatResponse> =
        if (!DEVICE_UUID.matches(attemptId)) DeviceCall.Rejected("invalid_attempt") else post("$attemptId/heartbeat", body, HeartbeatRequest.serializer(), HeartbeatResponse.serializer(), key)
    override suspend fun submit(attemptId: String, body: InferenceSubmission, key: String): DeviceCall<InferenceReceipt> =
        if (!DEVICE_UUID.matches(attemptId)) DeviceCall.Rejected("invalid_attempt") else post("$attemptId/result", body, InferenceSubmission.serializer(), InferenceReceipt.serializer(), key)

    private suspend fun <B, T> post(path: String, body: B, input: KSerializer<B>, output: KSerializer<T>, key: String): DeviceCall<T> {
        if (!DEVICE_UUID.matches(key)) return DeviceCall.Rejected("invalid_key")
        val access = tokens.current() ?: return DeviceCall.SessionExpired
        val encoded = json.encodeToString(input, body)
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_DEVICE_JSON_BYTES) return DeviceCall.Rejected("request_too_large")
        val request = Request.Builder().url(baseUrl.newBuilder().addPathSegments("api/v1/device-work").addPathSegments(path).build())
            .header("authorization", "Bearer $access").header("idempotency-key", key)
            .post(encoded.toRequestBody(jsonType)).build()
        return try {
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    if (response.code == 401) {
                        tokens.rejected(access)
                        return@withContext DeviceCall.SessionExpired
                    }
                    val source = response.body.source()
                    if (source.request(MAX_DEVICE_JSON_BYTES + 1L)) return@withContext DeviceCall.Unavailable("response_too_large")
                    val text = source.readUtf8()
                    if (response.isSuccessful) DeviceCall.Ok(json.decodeFromString(Envelope.serializer(output), text).data)
                    else {
                        val code = try { json.decodeFromString(ErrorEnvelope.serializer(), text).error.code } catch (_: IllegalArgumentException) { "unreadable_error" }
                        if (response.code == 429 || response.code >= 500) DeviceCall.Unavailable(code) else DeviceCall.Rejected(code)
                    }
                }
            }
        } catch (_: IOException) {
            DeviceCall.Unavailable("network")
        } catch (_: IllegalArgumentException) {
            DeviceCall.Unavailable("malformed_response")
        }
    }
}

internal val DEVICE_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
internal val DEVICE_HASH = Regex("^[0-9a-f]{64}$")
private const val MAX_DEVICE_JSON_BYTES = 256 * 1024
