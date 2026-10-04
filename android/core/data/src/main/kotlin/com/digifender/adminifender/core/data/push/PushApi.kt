package com.digifender.adminifender.core.data.push

import com.digifender.adminifender.core.data.network.AccessTokens
import com.digifender.adminifender.core.data.network.ErrorEnvelope
import com.digifender.adminifender.core.data.network.MAX_RESPONSE_BYTES
import com.digifender.adminifender.core.data.network.wireJson
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

enum class PushPermission(val wire: String) { GRANTED("granted"), DENIED("denied") }

sealed interface PushRegisterResult {
    data object Registered : PushRegisterResult

    data object SessionExpired : PushRegisterResult

    data class Rejected(val code: String) : PushRegisterResult

    data class Unavailable(val code: String) : PushRegisterResult
}

/** `POST /devices/{id}/push` (lld.md#notification-scheduling-and-delivery). The token is sent once and never logged. */
fun interface PushApi {
    suspend fun register(deviceId: String, token: String, permission: PushPermission, appVersion: String, idempotencyKey: String): PushRegisterResult
}

class HttpPushApi(private val client: OkHttpClient, private val baseUrl: HttpUrl, private val tokens: AccessTokens) : PushApi {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override suspend fun register(deviceId: String, token: String, permission: PushPermission, appVersion: String, idempotencyKey: String): PushRegisterResult {
        val access = tokens.current() ?: return PushRegisterResult.SessionExpired
        val body = buildJsonObject {
            put("token", token)
            put("permission", permission.wire)
            put("appVersion", appVersion)
        }
        val url = baseUrl.newBuilder().addPathSegments("api/v1/devices").addPathSegment(deviceId).addPathSegment("push").build()
        val request = Request.Builder().url(url)
            .header("authorization", "Bearer $access")
            .header("idempotency-key", idempotencyKey)
            .post(body.toString().toRequestBody(jsonType))
            .build()
        return try {
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    val source = response.body.source()
                    if (source.request(MAX_RESPONSE_BYTES + 1)) throw IOException("response_too_large")
                    val text = source.readUtf8()
                    when {
                        response.isSuccessful -> PushRegisterResult.Registered
                        response.code == 401 -> PushRegisterResult.SessionExpired.also { tokens.rejected(access) }
                        response.code == 429 || response.code >= 500 -> PushRegisterResult.Unavailable(code(text))
                        else -> PushRegisterResult.Rejected(code(text))
                    }
                }
            }
        } catch (_: IOException) {
            PushRegisterResult.Unavailable("network")
        }
    }

    private fun code(text: String) = try {
        wireJson.decodeFromString(ErrorEnvelope.serializer(), text).error.code
    } catch (_: SerializationException) {
        "unreadable_error"
    } catch (_: IllegalArgumentException) {
        "unreadable_error"
    }
}

/**
 * What was last registered, as a digest only, so the app re-registers exactly when the token, permission, device
 * or app version changes and never keeps a second plaintext copy of the token.
 */
fun registrationDigest(deviceId: String, token: String, permission: PushPermission, appVersion: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest("$deviceId\n$token\n${permission.wire}\n$appVersion".encodeToByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}
