package com.digifender.adminifender.core.data.chat

import com.digifender.adminifender.core.data.network.AccessTokens
import com.digifender.adminifender.core.data.network.ErrorEnvelope
import com.digifender.adminifender.core.data.network.MAX_RESPONSE_BYTES
import com.digifender.adminifender.core.data.network.ReadResult
import com.digifender.adminifender.core.data.network.wireJson
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Bounded, authenticated chat transport. Response bodies and questions are never logged. */
class HttpChatApi(client: OkHttpClient, private val baseUrl: HttpUrl, private val tokens: AccessTokens) : ChatApi {
    // Recovery belongs to the repository; redirects must not forward owner content to another origin.
    private val client = client.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).callTimeout(30, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun url(path: String, before: String? = null): HttpUrl = baseUrl.newBuilder()
        .addPathSegments("api/v1/chat").addPathSegments(path)
        .apply { before?.let { addQueryParameter("before", it) } }.build()

    private suspend fun <T> execute(request: Request.Builder, decode: (Response) -> T): T? {
        val token = tokens.current() ?: return null
        return withContext(Dispatchers.IO) {
            client.newCall(request.header("authorization", "Bearer $token").build()).execute().use { response ->
                if (response.code == 401) tokens.rejected(token)
                // Headers can arrive before the body. Read, bound, parse and close on IO, even after cancellation.
                decode(response)
            }
        }
    }

    private fun Response.text(): String {
        if (body.contentLength() > MAX_RESPONSE_BYTES || body.source().request(MAX_RESPONSE_BYTES + 1)) throw IOException("response_too_large")
        return body.source().readUtf8()
    }

    private fun error(text: String): String = try {
        wireJson.decodeFromString(ErrorEnvelope.serializer(), text).error.code
    } catch (_: IllegalArgumentException) {
        "malformed_response"
    }

    private fun <T> decode(text: String, serializer: KSerializer<T>): T =
        wireJson.decodeFromJsonElement(serializer, wireJson.parseToJsonElement(text).jsonObject.getValue("data"))

    private suspend fun <T> read(url: HttpUrl, serializer: KSerializer<T>): ReadResult<T> {
        return try {
            execute(Request.Builder().url(url).get()) {
                val text = it.text()
                when {
                    it.code == 401 -> ReadResult.SessionExpired
                    it.isSuccessful -> ReadResult.Ok(decode(text, serializer))
                    else -> ReadResult.Unavailable(error(text))
                }
            } ?: ReadResult.SessionExpired
        } catch (_: IOException) {
            ReadResult.Unavailable("network")
        } catch (_: IllegalArgumentException) {
            ReadResult.Unavailable("malformed_response")
        } catch (_: NoSuchElementException) {
            ReadResult.Unavailable("malformed_response")
        }
    }

    private suspend fun <T> post(path: String, key: String, body: JsonObject, serializer: KSerializer<T>): ChatWriteResult<T> {
        return try {
            execute(Request.Builder().url(url(path)).header("idempotency-key", key).post(body.toString().toRequestBody(jsonType))) {
                val text = it.text()
                val code = if (it.isSuccessful) null else error(text)
                when {
                    it.code == 401 -> ChatWriteResult.SessionExpired
                    it.isSuccessful -> ChatWriteResult.Ok(decode(text, serializer))
                    it.code == 429 || it.code >= 500 || code in setOf("request_in_progress", "malformed_response") -> ChatWriteResult.NoAnswer
                    else -> ChatWriteResult.Rejected(requireNotNull(code))
                }
            } ?: ChatWriteResult.SessionExpired
        } catch (_: IOException) {
            ChatWriteResult.NoAnswer
        } catch (_: IllegalArgumentException) {
            ChatWriteResult.NoAnswer
        } catch (_: NoSuchElementException) {
            ChatWriteResult.NoAnswer
        }
    }

    override suspend fun createThread(scope: ChatScope, key: String): ChatWriteResult<ChatThread> {
        if (!scope.valid()) return ChatWriteResult.Rejected("invalid_scope")
        return post("threads", key, buildJsonObject {
            put("scope", buildJsonObject {
                put("kind", scope.kind)
                scope.id?.let { put("id", it) }
            })
        }, ChatThread.serializer())
    }

    override suspend fun submitTurn(threadId: String, turnId: String, content: String, key: String): ChatWriteResult<ChatAccepted> {
        if (!isChatId(threadId) || !isChatId(turnId) || content.isBlank() || content.length > MAX_CHAT_QUESTION_CHARS) return ChatWriteResult.Rejected("invalid_request")
        return post("threads/$threadId/turns", key, buildJsonObject { put("turnId", turnId); put("content", content) }, ChatAccepted.serializer())
    }

    override suspend fun turn(turnId: String): ReadResult<ChatTurn> =
        if (!isChatId(turnId)) ReadResult.Unavailable("not_found") else read(url("turns/$turnId"), ChatTurn.serializer())

    override suspend fun history(threadId: String, before: String?): ReadResult<ChatHistory> =
        if (!isChatId(threadId) || (before != null && !isChatId(before))) ReadResult.Unavailable("not_found")
        else read(url("threads/$threadId/turns", before), ChatHistory.serializer())

    override suspend fun cancel(turnId: String, key: String): ChatWriteResult<ChatAccepted> =
        if (!isChatId(turnId)) ChatWriteResult.Rejected("not_found") else post("turns/$turnId/cancel", key, buildJsonObject {}, ChatAccepted.serializer())
}
