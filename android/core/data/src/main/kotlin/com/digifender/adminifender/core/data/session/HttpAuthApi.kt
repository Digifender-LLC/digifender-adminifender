package com.digifender.adminifender.core.data.session

import com.digifender.adminifender.core.data.network.ErrorEnvelope
import com.digifender.adminifender.core.data.network.MAX_RESPONSE_BYTES
import com.digifender.adminifender.core.data.network.wireJson
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** OkHttp adapter for [AuthApi]. Tokens and assertions are never logged; errors are reduced to their fixed codes. */
class HttpAuthApi(private val client: OkHttpClient, private val baseUrl: HttpUrl) : AuthApi {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun url(path: String): HttpUrl = baseUrl.newBuilder().addPathSegments("api/v1/auth").addPathSegments(path).build()

    private suspend fun <T> post(path: String, body: JsonObject, authorization: String?, parse: (JsonObject) -> T): AuthResult<T> = try {
        val request = Request.Builder().url(url(path)).post(body.toString().toRequestBody(jsonType))
        if (authorization != null) request.header("authorization", authorization)
        withContext(Dispatchers.IO) {
            client.newCall(request.build()).execute().use { response ->
                val source = response.body.source()
                if (source.request(MAX_RESPONSE_BYTES + 1)) throw IOException("response_too_large")
                val text = source.readUtf8()
                when {
                    response.isSuccessful -> AuthResult.Ok(parse(wireJson.parseToJsonElement(text).jsonObject.getValue("data").jsonObject))
                    response.code == 429 || response.code >= 500 -> AuthResult.Unavailable(errorCode(text))
                    else -> AuthResult.Rejected(errorCode(text))
                }
            }
        }
    } catch (_: IOException) {
        AuthResult.Unavailable("network")
    } catch (_: IllegalArgumentException) {
        // Malformed JSON, wrong field types and unparseable timestamps.
        AuthResult.Unavailable("malformed_response")
    } catch (_: NoSuchElementException) {
        AuthResult.Unavailable("malformed_response")
    }

    private fun errorCode(text: String): String = try {
        wireJson.decodeFromString(ErrorEnvelope.serializer(), text).error.code
    } catch (_: SerializationException) {
        "unreadable_error"
    } catch (_: IllegalArgumentException) {
        "unreadable_error"
    }

    override suspend fun passkeyOptions(purpose: PasskeyPurpose, deviceId: String?, accessToken: String?): AuthResult<PasskeyChallenge> {
        val body = buildJsonObject {
            put("purpose", purpose.wire)
            if (deviceId != null && purpose == PasskeyPurpose.SIGN_IN) put("deviceId", deviceId)
        }
        return post("passkey/options", body, accessToken?.let { "Bearer $it" }) { data ->
            PasskeyChallenge(data.string("challengeId"), data.getValue("options").jsonObject.toString())
        }
    }

    override suspend fun signInVerify(challengeId: String, assertion: JsonObject, deviceName: String, capabilities: JsonObject): AuthResult<SignedIn> {
        val body = buildJsonObject {
            put("challengeId", challengeId)
            put("assertion", assertion)
            put("deviceCapabilities", capabilities)
            put("deviceName", deviceName)
        }
        return post("passkey/verify", body, null) { data -> SignedIn(data.string("deviceId"), tokens(data)) }
    }

    override suspend fun recentVerify(challengeId: String, assertion: JsonObject): AuthResult<Long> {
        val body = buildJsonObject {
            put("challengeId", challengeId)
            put("assertion", assertion)
        }
        return post("passkey/verify", body, null) { data -> instant(data.string("recentAuthAt")) }
    }

    override suspend fun refresh(refreshToken: String): AuthResult<IssuedTokens> =
        post("refresh", JsonObject(emptyMap()), "Refresh $refreshToken", ::tokens)

    override suspend fun logout(accessToken: String): AuthResult<Unit> = post("logout", JsonObject(emptyMap()), "Bearer $accessToken") { }

    private companion object {
        fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

        fun instant(text: String): Long = try {
            Instant.parse(text).toEpochMilli()
        } catch (_: DateTimeParseException) {
            throw IllegalArgumentException("bad_timestamp")
        }

        fun tokens(data: JsonObject) = IssuedTokens(
            accessToken = data.string("accessToken"),
            refreshToken = data.string("refreshToken"),
            accessExpiresAt = instant(data.string("accessExpiresAt")),
            refreshExpiresAt = instant(data.string("refreshExpiresAt")),
        )
    }
}
