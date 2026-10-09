package com.digifender.adminifender.core.data.network

import com.digifender.adminifender.core.data.files.blockingWithAbort
import com.digifender.adminifender.core.model.ConcernAction
import com.digifender.adminifender.core.model.SendResult
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Supplies the current bearer token; null when signed out. Refresh is the session layer's job. */
fun interface AccessTokens {
    suspend fun current(): String?

    /** The server refused [token] with 401; the next [current] call should not hand it out again. */
    suspend fun rejected(token: String) {}
}

/** Responses larger than this are refused rather than buffered (lld.md#contract-conventions). */
const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024

/**
 * OkHttp adapter for [AdminifenderApi]. Error bodies are reduced to their fixed codes; nothing from a response is
 * logged.
 */
class HttpApi(private val client: OkHttpClient, private val baseUrl: HttpUrl, private val tokens: AccessTokens) : AdminifenderApi, ArchiveApi, BriefingApi, ChatApi {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun url(path: String, query: Map<String, String?> = emptyMap()): HttpUrl {
        val builder = baseUrl.newBuilder().addPathSegments("api/v1").addPathSegments(path.trimStart('/'))
        query.forEach { (k, v) -> if (v != null) builder.addQueryParameter(k, v) }
        return builder.build()
    }

    private suspend fun execute(request: Request.Builder): Response? {
        val token = tokens.current() ?: return null
        val call = client.newCall(request.header("authorization", "Bearer $token").build())
        // Cancelling the caller (sign-out, leaving the screen) cancels the call; nothing blocks a thread while waiting for headers.
        val response = call.await()
        if (response.code == 401) tokens.rejected(token)
        try {
            // The body is read here, on the IO dispatcher and up to the bound, so the callers' later reads never touch the socket
            // (they run on whatever dispatcher the view model uses, which may be the main thread).
            blockingWithAbort(call::cancel) { response.body.source().request(MAX_RESPONSE_BYTES + 1) }
        } catch (e: IOException) {
            response.close()
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            response.close()
            throw e
        }
        return response
    }

    private fun Response.boundedBody(): String {
        val body = body
        val length = body.contentLength()
        if (length > MAX_RESPONSE_BYTES) throw IOException("response_too_large")
        val source = body.source()
        if (source.request(MAX_RESPONSE_BYTES + 1)) throw IOException("response_too_large")
        return source.readUtf8()
    }

    private fun errorCode(text: String): Pair<String, JsonObject?> = try {
        val error = wireJson.decodeFromString(ErrorEnvelope.serializer(), text).error
        error.code to (error.details as? JsonObject)
    } catch (_: Exception) {
        "unreadable_error" to null
    }

    private suspend fun <T> read(url: HttpUrl, serializer: KSerializer<T>): ReadResult<T> = try {
        val response = execute(Request.Builder().url(url).get()) ?: return ReadResult.SessionExpired
        response.use {
            val text = it.boundedBody()
            when {
                it.isSuccessful -> ReadResult.Ok(wireJson.decodeFromJsonElement(serializer, wireJson.parseToJsonElement(text).jsonObject.getValue("data")))
                it.code == 401 -> ReadResult.SessionExpired
                it.code == 410 -> ReadResult.CursorExpired
                else -> ReadResult.Unavailable(errorCode(text).first)
            }
        }
    } catch (_: IOException) {
        ReadResult.Unavailable("network")
    } catch (_: IllegalArgumentException) {
        ReadResult.Unavailable("malformed_response")
    } catch (_: NoSuchElementException) {
        // A success body without its `data` envelope key.
        ReadResult.Unavailable("malformed_response")
    }

    override suspend fun changes(after: Long, limit: Int) = read(url("sync/changes", mapOf("after" to after.toString(), "limit" to limit.toString())), ChangesPageDto.serializer())

    override suspend fun bootstrap(cursor: String?, limit: Int) = read(url("sync/bootstrap", mapOf("cursor" to cursor, "limit" to limit.toString())), BootstrapPageDto.serializer())

    override suspend fun source(id: String): ReadResult<SourceDetailDto> {
        if (!isSourceId(id)) return ReadResult.Unavailable("not_found")
        return read(url("sources/$id"), SourceDetailDto.serializer())
    }

    override suspend fun chunks(id: String, cursor: String?, limit: Int): ReadResult<ChunksPageDto> {
        if (!isSourceId(id)) return ReadResult.Unavailable("not_found")
        return read(url("sources/$id/chunks", mapOf("cursor" to cursor, "limit" to limit.toString())), ChunksPageDto.serializer())
    }

    override suspend fun search(request: SearchRequest, cursor: String?, limit: Int): ReadResult<SearchPageDto> = read(
        url(
            "search",
            mapOf(
                "q" to request.q,
                "view" to request.view.wire,
                "sender" to request.sender,
                "from" to request.fromMs?.let { Instant.ofEpochMilli(it).toString() },
                "to" to request.toMs?.let { Instant.ofEpochMilli(it).toString() },
                "mode" to "exact",
                "limit" to limit.toString(),
                "cursor" to cursor,
            ),
        ),
        SearchPageDto.serializer(),
    )

    override suspend fun currentBriefing(): ReadResult<BriefingDto> = read(url("briefings/current"), BriefingDto.serializer())

    override suspend fun reviewBriefing(id: String, idempotencyKey: String): ReviewResult {
        if (!isBriefingId(id)) return ReviewResult.NotFound
        return try {
            val request = Request.Builder().url(url("briefings/$id/review")).header("idempotency-key", idempotencyKey).post("{}".toRequestBody(jsonType))
            val response = execute(request) ?: return ReviewResult.SessionExpired
            response.use {
                val text = it.boundedBody()
                if (it.isSuccessful) {
                    val done = wireJson.decodeFromJsonElement(BriefingReviewedDto.serializer(), wireJson.parseToJsonElement(text).jsonObject.getValue("data"))
                    return ReviewResult.Reviewed(done.id, done.reviewedThroughSeq, Instant.parse(done.reviewedAt).toEpochMilli())
                }
                val (code, _) = errorCode(text)
                when {
                    it.code == 401 -> ReviewResult.SessionExpired
                    it.code == 404 -> ReviewResult.NotFound
                    code == "already_reviewed" -> ReviewResult.AlreadyReviewed
                    // A request still being processed under this key is retryable; the same key then replays its answer.
                    it.code == 429 || it.code >= 500 || code == "request_in_progress" -> ReviewResult.NoAnswer
                    else -> ReviewResult.Rejected(code)
                }
            }
        } catch (_: IOException) {
            ReviewResult.NoAnswer
        } catch (_: IllegalArgumentException) {
            ReviewResult.NoAnswer
        } catch (_: NoSuchElementException) {
            ReviewResult.NoAnswer
        } catch (_: DateTimeParseException) {
            ReviewResult.NoAnswer
        }
    }

    override suspend fun createThread(idempotencyKey: String, scope: ChatScope): ThreadResult {
        if (!scope.wellFormed) return ThreadResult.Refused("invalid_scope")
        val body = buildJsonObject {
            put("scope", buildJsonObject {
                put("kind", scope.kind.wire)
                scope.id?.let { put("id", it) }
            })
        }.toString()
        return try {
            val response = execute(Request.Builder().url(url("chat/threads")).header("idempotency-key", idempotencyKey).post(body.toRequestBody(jsonType))) ?: return ThreadResult.SessionExpired
            response.use {
                val text = it.boundedBody()
                if (it.isSuccessful) {
                    return ThreadResult.Created(wireJson.decodeFromJsonElement(ThreadDto.serializer(), wireJson.parseToJsonElement(text).jsonObject.getValue("data")))
                }
                val (code, _) = errorCode(text)
                when {
                    it.code == 401 -> ThreadResult.SessionExpired
                    it.code == 429 || it.code >= 500 || code == "request_in_progress" -> ThreadResult.NoAnswer
                    else -> ThreadResult.Refused(code)
                }
            }
        } catch (_: IOException) {
            ThreadResult.NoAnswer
        } catch (_: IllegalArgumentException) {
            ThreadResult.NoAnswer
        } catch (_: NoSuchElementException) {
            ThreadResult.NoAnswer
        }
    }

    override suspend fun postTurn(threadId: String, turnId: String, content: String): PostResult {
        if (!isRecordId(threadId) || !isRecordId(turnId)) return PostResult.Refused("invalid_id")
        val body = buildJsonObject {
            put("turnId", turnId)
            put("content", content)
        }.toString()
        return try {
            // The turn ID is also the Idempotency-Key, so a resend can only ever be the same request.
            val request = Request.Builder().url(url("chat/threads/$threadId/turns")).header("idempotency-key", turnId).post(body.toRequestBody(jsonType))
            val response = execute(request) ?: return PostResult.SessionExpired
            response.use {
                val text = it.boundedBody()
                if (it.isSuccessful) {
                    val accepted = wireJson.decodeFromJsonElement(TurnAcceptedDto.serializer(), wireJson.parseToJsonElement(text).jsonObject.getValue("data"))
                    // An answer about another turn confirms nothing about this one.
                    return if (accepted.turnId == turnId) PostResult.Accepted(accepted.state) else PostResult.NoAnswer
                }
                val (code, _) = errorCode(text)
                when {
                    it.code == 401 -> PostResult.SessionExpired
                    it.code == 429 || it.code >= 500 || code == "request_in_progress" -> PostResult.NoAnswer
                    else -> PostResult.Refused(code)
                }
            }
        } catch (_: IOException) {
            PostResult.NoAnswer
        } catch (_: IllegalArgumentException) {
            PostResult.NoAnswer
        } catch (_: NoSuchElementException) {
            PostResult.NoAnswer
        }
    }

    override suspend fun turn(turnId: String): ReadResult<TurnDto> {
        if (!isRecordId(turnId)) return ReadResult.Unavailable("not_found")
        return read(url("chat/turns/$turnId"), TurnDto.serializer())
    }

    override suspend fun history(threadId: String, before: String?): ReadResult<HistoryDto> {
        if (!isRecordId(threadId) || (before != null && !isRecordId(before))) return ReadResult.Unavailable("not_found")
        return read(url("chat/threads/$threadId/turns", mapOf("before" to before)), HistoryDto.serializer())
    }

    override suspend fun cancel(turnId: String, idempotencyKey: String): CancelResult {
        if (!isRecordId(turnId)) return CancelResult.NotFound
        return try {
            val response = execute(Request.Builder().url(url("chat/turns/$turnId/cancel")).header("idempotency-key", idempotencyKey).post("{}".toRequestBody(jsonType))) ?: return CancelResult.SessionExpired
            response.use {
                val text = it.boundedBody()
                if (it.isSuccessful) return CancelResult.Cancelled
                val (code, details) = errorCode(text)
                when {
                    it.code == 401 -> CancelResult.SessionExpired
                    it.code == 404 -> CancelResult.NotFound
                    code == "already_dispatched" -> CancelResult.AlreadyDispatched
                    code == "not_cancellable" -> CancelResult.NotCancellable(details?.get("state")?.jsonPrimitive?.contentOrNull)
                    it.code == 429 || it.code >= 500 || code == "request_in_progress" -> CancelResult.NoAnswer
                    else -> CancelResult.Refused(code)
                }
            }
        } catch (_: IOException) {
            CancelResult.NoAnswer
        } catch (_: IllegalArgumentException) {
            CancelResult.NoAnswer
        } catch (_: NoSuchElementException) {
            CancelResult.NoAnswer
        }
    }

    override suspend fun concernAction(concernId: String, idempotencyKey: String, expectedRevision: Int, action: ConcernAction): SendResult {
        val body = actionBody(expectedRevision, action).toString().toRequestBody(jsonType)
        return try {
            val response = execute(Request.Builder().url(url("concerns/$concernId/actions")).header("idempotency-key", idempotencyKey).post(body)) ?: return SendResult.SessionExpired
            response.use {
                val text = it.boundedBody()
                if (it.isSuccessful) {
                    val revision = wireJson.parseToJsonElement(text).jsonObject.getValue("data").jsonObject.getValue("concern").jsonObject.getValue("revision").jsonPrimitive.int
                    return SendResult.Accepted(revision)
                }
                val (code, details) = errorCode(text)
                when {
                    it.code == 401 -> SendResult.SessionExpired
                    code == "stale_revision" -> SendResult.StaleRevision(details?.get("currentRevision")?.jsonPrimitive?.intOrNull)
                    it.code == 429 || it.code >= 500 -> SendResult.NoAnswer
                    else -> SendResult.Rejected(code)
                }
            }
        } catch (_: IOException) {
            // The request may have reached the server; the same key makes the resend safe.
            SendResult.NoAnswer
        } catch (_: IllegalArgumentException) {
            SendResult.NoAnswer
        } catch (_: NoSuchElementException) {
            SendResult.NoAnswer
        }
    }

    companion object {
        private fun iso(ms: Long?) = if (ms == null) JsonNull else JsonPrimitive(Instant.ofEpochMilli(ms).toString())

        /** `{expectedRevision, kind, args}` exactly as the backend validates it. */
        fun actionBody(expectedRevision: Int, action: ConcernAction): JsonObject = buildJsonObject {
            put("expectedRevision", expectedRevision)
            put("kind", action.kind.wire)
            when (action) {
                is ConcernAction.Snooze -> put("args", buildJsonObject { put("until", iso(action.untilMs)) })
                is ConcernAction.Wait -> put("args", buildJsonObject {
                    put("note", action.note?.let(::JsonPrimitive) ?: JsonNull)
                    put("awaitedParty", action.awaitedParty?.let(::JsonPrimitive) ?: JsonNull)
                    put("followUpAt", iso(action.followUpAtMs))
                })
                is ConcernAction.SetDue -> put("args", buildJsonObject {
                    put("at", iso(action.atMs))
                    put("zone", action.zone?.let(::JsonPrimitive) ?: JsonNull)
                    action.sourceText?.let { put("sourceText", it) }
                })
                else -> Unit
            }
        }
    }
}
