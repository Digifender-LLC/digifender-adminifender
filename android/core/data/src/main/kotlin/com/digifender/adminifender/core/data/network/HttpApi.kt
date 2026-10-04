package com.digifender.adminifender.core.data.network

import com.digifender.adminifender.core.data.device.ClaimDto
import com.digifender.adminifender.core.data.device.DeviceWorkApi
import com.digifender.adminifender.core.data.device.SubmitDto
import com.digifender.adminifender.core.data.device.WorkResult
import com.digifender.adminifender.core.data.extraction.Batch
import com.digifender.adminifender.core.data.extraction.BatchAckDto
import com.digifender.adminifender.core.data.extraction.DownloadResult
import com.digifender.adminifender.core.data.extraction.ExtractionApi
import com.digifender.adminifender.core.data.extraction.ExtractionClaimDto
import com.digifender.adminifender.core.data.extraction.ExtractionWorkDto
import com.digifender.adminifender.core.data.extraction.PageDto
import com.digifender.adminifender.core.model.ConcernAction
import com.digifender.adminifender.core.model.Readiness
import com.digifender.adminifender.core.model.SendResult
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
class HttpApi(private val client: OkHttpClient, private val baseUrl: HttpUrl, private val tokens: AccessTokens) : AdminifenderApi, ArchiveApi, ChatApi, DeviceWorkApi, ExtractionApi, OwnerApi {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun url(path: String, query: Map<String, String?> = emptyMap()): HttpUrl {
        val builder = baseUrl.newBuilder().addPathSegments("api/v1").addPathSegments(path.trimStart('/'))
        query.forEach { (k, v) -> if (v != null) builder.addQueryParameter(k, v) }
        return builder.build()
    }

    private suspend fun execute(request: Request.Builder): Response? {
        val token = tokens.current() ?: return null
        val response = withContext(Dispatchers.IO) { client.newCall(request.header("authorization", "Bearer $token").build()).execute() }
        if (response.code == 401) tokens.rejected(token)
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

    /** A JSON mutation whose `data` decodes to [serializer]; 429 and 5xx mean the outcome is unknown. */
    private suspend fun <T> post(path: String, idempotencyKey: String, body: JsonObject, serializer: KSerializer<T>): CallResult<T> = try {
        val request = Request.Builder().url(url(path)).header("idempotency-key", idempotencyKey).post(body.toString().toRequestBody(jsonType))
        val response = execute(request) ?: return CallResult.SessionExpired
        response.use {
            val text = it.boundedBody()
            when {
                it.isSuccessful -> CallResult.Ok(wireJson.decodeFromJsonElement(serializer, wireJson.parseToJsonElement(text).jsonObject.getValue("data")))
                it.code == 401 -> CallResult.SessionExpired
                it.code == 429 || it.code >= 500 -> CallResult.NoAnswer
                else -> CallResult.Refused(errorCode(text).first)
            }
        }
    } catch (_: IOException) {
        CallResult.NoAnswer
    } catch (_: IllegalArgumentException) {
        CallResult.NoAnswer
    } catch (_: NoSuchElementException) {
        CallResult.NoAnswer
    }

    override suspend fun createThread(idempotencyKey: String, scope: ScopeDto) = post(
        "chat/threads",
        idempotencyKey,
        buildJsonObject { put("scope", buildJsonObject { put("kind", scope.kind); scope.id?.let { put("id", it) } }) },
        ChatThreadDto.serializer(),
    )

    override suspend fun postTurn(threadId: String, turnId: String, content: String) =
        post("chat/threads/$threadId/turns", turnId, buildJsonObject { put("turnId", turnId); put("content", content) }, TurnStateDto.serializer())

    override suspend fun turn(turnId: String) = read(url("chat/turns/$turnId"), TurnDto.serializer())

    override suspend fun cancelTurn(turnId: String, idempotencyKey: String) = post("chat/turns/$turnId/cancel", idempotencyKey, JsonObject(emptyMap()), TurnStateDto.serializer())

    override suspend fun resumeExtraction(attachmentId: String, idempotencyKey: String) = postUnit("attachments/$attachmentId/resume-extraction", idempotencyKey, JsonObject(emptyMap()))

    private suspend fun postUnit(path: String, key: String, body: JsonObject): CallResult<Unit> = when (val r = post(path, key, body, JsonObject.serializer())) {
        is CallResult.Ok -> CallResult.Ok(Unit)
        is CallResult.Refused -> r
        CallResult.NoAnswer -> CallResult.NoAnswer
        CallResult.SessionExpired -> CallResult.SessionExpired
    }

    override suspend fun claim(idempotencyKey: String, readiness: Readiness) = post("device-work/claim", idempotencyKey, readinessBody(readiness), ClaimDto.serializer())

    override suspend fun heartbeat(attemptId: String, idempotencyKey: String, leaseToken: String) =
        postUnit("device-work/$attemptId/heartbeat", idempotencyKey, buildJsonObject { put("leaseToken", leaseToken) })

    override suspend fun submit(attemptId: String, idempotencyKey: String, result: WorkResult) = post(
        "device-work/$attemptId/result",
        idempotencyKey,
        buildJsonObject {
            put("leaseToken", result.leaseToken)
            put("inputDigest", result.inputDigest)
            put("policyRevision", result.policyRevision)
            put("outputJson", result.outputJson)
            put("modelManifestHash", result.modelManifestHash)
        },
        SubmitDto.serializer(),
    )

    override suspend fun decline(attemptId: String, idempotencyKey: String, leaseToken: String, reason: String) =
        postUnit("device-work/$attemptId/decline", idempotencyKey, buildJsonObject { put("leaseToken", leaseToken); put("reason", reason) })

    override suspend fun claimExtraction(idempotencyKey: String, charging: Boolean, batteryPercent: Int, thermal: String) = post(
        "device-extraction/claim",
        idempotencyKey,
        buildJsonObject {
            put("charging", charging)
            put("batteryPercent", batteryPercent)
            put("thermal", thermal)
        },
        ExtractionClaimDto.serializer(),
    )

    override suspend fun sendBatch(work: ExtractionWorkDto, idempotencyKey: String, batch: Batch) = post(
        "device-extraction/${work.attachmentId}/batch",
        idempotencyKey,
        buildJsonObject {
            put("leaseToken", work.leaseToken)
            put("contentHash", work.contentHash)
            put("policyRevision", work.policyRevision)
            put("generationId", work.generationId)
            put("batchOrdinal", batch.ordinal)
            put("pages", JsonArray(batch.pages.map { wireJson.encodeToJsonElement(PageDto.serializer(), it) }))
            put("complete", batch.complete)
            if (batch.paused) put("paused", true)
            batch.manifestHash?.let { put("manifestHash", it) }
        },
        BatchAckDto.serializer(),
    )

    override suspend fun failExtraction(attachmentId: String, idempotencyKey: String, leaseToken: String, reason: String) =
        postUnit("device-extraction/$attachmentId/fail", idempotencyKey, buildJsonObject { put("leaseToken", leaseToken); put("reason", reason) })

    override suspend fun download(attachmentId: String, target: File, maxBytes: Long, sha256: String): DownloadResult = try {
        val response = execute(Request.Builder().url(url("attachments/$attachmentId/content")).get()) ?: return DownloadResult.SESSION_EXPIRED
        response.use {
            when {
                it.code == 401 -> DownloadResult.SESSION_EXPIRED
                it.code == 404 -> DownloadResult.GONE
                !it.isSuccessful -> DownloadResult.UNAVAILABLE
                else -> withContext(Dispatchers.IO) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    val buffer = ByteArray(1 shl 16)
                    it.body.byteStream().use { input ->
                        target.outputStream().use { out ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                total += n
                                if (total > maxBytes) return@withContext DownloadResult.TOO_LARGE
                                digest.update(buffer, 0, n)
                                out.write(buffer, 0, n)
                            }
                        }
                    }
                    if (digest.digest().joinToString("") { b -> "%02x".format(b) } == sha256) DownloadResult.OK else DownloadResult.WRONG_HASH
                }
            }
        }
    } catch (_: IOException) {
        DownloadResult.UNAVAILABLE
    }

    override suspend fun briefing() = read(url("briefings/current"), BriefingDto.serializer())

    override suspend fun operations() = read(url("operations/overview"), OperationsDto.serializer())

    override suspend fun reviewBriefing(id: String, idempotencyKey: String) = postUnit("briefings/$id/review", idempotencyKey, JsonObject(emptyMap()))

    override suspend fun devices() = read(url("devices"), DevicesDto.serializer())

    override suspend fun createReview(idempotencyKey: String, effect: String, params: JsonObject) =
        post("reviews", idempotencyKey, buildJsonObject { put("effect", effect); put("params", params) }, ReviewDto.serializer())

    override suspend fun confirmReview(id: String, idempotencyKey: String, paramsDigest: String) =
        post("reviews/$id/confirm", idempotencyKey, buildJsonObject { put("paramsDigest", paramsDigest) }, OperationDto.serializer())

    override suspend fun createExport(idempotencyKey: String) = post("exports", idempotencyKey, JsonObject(emptyMap()), ExportDto.serializer())

    override suspend fun export(id: String) = read(url("exports/$id"), ExportDto.serializer())

    override suspend fun saveExport(id: String, out: OutputStream) = stream(url("exports/$id/content"), out, maxBytes = null, sha256 = null)

    override suspend fun saveAttachment(id: String, out: OutputStream, maxBytes: Long, sha256: String) = stream(url("attachments/$id/content"), out, maxBytes, sha256)

    /** Copies a binary response to [out] in blocks, with an optional size cap and SHA-256 check. */
    private suspend fun stream(target: HttpUrl, out: OutputStream, maxBytes: Long?, sha256: String?): StreamResult = try {
        val response = execute(Request.Builder().url(target).get()) ?: return StreamResult.SESSION_EXPIRED
        response.use {
            when {
                it.code == 401 -> StreamResult.SESSION_EXPIRED
                it.code == 404 -> StreamResult.GONE
                it.code == 410 -> StreamResult.EXPIRED
                !it.isSuccessful -> StreamResult.UNAVAILABLE
                else -> withContext(Dispatchers.IO) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    val buffer = ByteArray(1 shl 16)
                    it.body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (maxBytes != null && total > maxBytes) return@withContext StreamResult.TOO_LARGE
                            digest.update(buffer, 0, n)
                            out.write(buffer, 0, n)
                        }
                    }
                    if (sha256 == null || digest.digest().joinToString("") { b -> "%02x".format(b) } == sha256) StreamResult.OK else StreamResult.WRONG_HASH
                }
            }
        }
    } catch (_: IOException) {
        StreamResult.UNAVAILABLE
    }

    companion object {
        /** Readiness with explicit nulls: the server requires every field. */
        fun readinessBody(r: Readiness): JsonObject = buildJsonObject {
            put("foreground", r.foreground)
            put("charging", r.charging)
            put("batteryPercent", r.batteryPercent)
            put("thermal", r.thermal.wire)
            put("modelId", r.modelId?.let(::JsonPrimitive) ?: JsonNull)
            put("modelManifestHash", r.modelManifestHash?.let(::JsonPrimitive) ?: JsonNull)
        }


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
