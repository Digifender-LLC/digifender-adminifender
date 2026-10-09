package com.digifender.adminifender.core.data.network

import com.digifender.adminifender.core.data.files.blockingWithAbort
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Runs the call without blocking a thread, and cancels it (closing the socket) when the calling coroutine is cancelled. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
}

internal class ErrorInfo(val code: String, val details: JsonObject?)

/** What the review, export and attachment clients share: URLs, the bearer token, bounded bodies and fixed error codes. */
internal class HttpCore(private val client: OkHttpClient, private val baseUrl: HttpUrl, private val tokens: AccessTokens) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun url(vararg segments: String): HttpUrl {
        val builder = baseUrl.newBuilder().addPathSegments("api/v1")
        segments.forEach { builder.addPathSegment(it) }
        return builder.build()
    }

    fun json(text: String) = text.toRequestBody(jsonType)

    /** Sends [request] with the current token. Null means there is no session; a 401 tells the session layer the token was refused. */
    suspend fun call(request: Request.Builder): Pair<Call, Response>? {
        val token = tokens.current() ?: return null
        val call = client.newCall(request.header("authorization", "Bearer $token").build())
        val response = call.await()
        if (response.code == 401) tokens.rejected(token)
        return call to response
    }

    /**
     * The response body as text, read on the IO dispatcher (never on the caller's, which may be the main thread) and
     * refused above [MAX_RESPONSE_BYTES]. Cancelling the caller closes the connection under the blocked read.
     */
    suspend fun text(call: Call, response: Response): String = blockingWithAbort(call::cancel) {
        val length = response.body.contentLength()
        if (length > MAX_RESPONSE_BYTES) throw IOException("response_too_large")
        val source = response.body.source()
        if (source.request(MAX_RESPONSE_BYTES + 1)) throw IOException("response_too_large")
        source.readUtf8()
    }

    fun error(text: String): ErrorInfo = try {
        val error = wireJson.decodeFromString(ErrorEnvelope.serializer(), text).error
        ErrorInfo(error.code, error.details as? JsonObject)
    } catch (_: IllegalArgumentException) {
        ErrorInfo("unreadable_error", null)
    }

    fun data(text: String): JsonElement = wireJson.parseToJsonElement(text).jsonObject["data"] ?: throw WireException("$.data: missing")

    /** True when a failed answer says nothing about whether the request took effect: the Worker was busy, broken or unreachable. */
    fun inconclusive(status: Int, code: String) = status == 429 || status >= 500 || code == "request_in_progress"
}

class HttpReviewApi(client: OkHttpClient, baseUrl: HttpUrl, tokens: AccessTokens) : ReviewApi {
    private val core = HttpCore(client, baseUrl, tokens)

    override suspend fun prepareDeletion(idempotencyKey: String, sourceIds: List<String>): PrepareResult {
        if (sourceIds.isEmpty() || sourceIds.size > MAX_DELETE_SOURCES || sourceIds.any { !isUuid(it) }) return PrepareResult.Refused("invalid_request")
        val request = Request.Builder().url(core.url("reviews")).header("idempotency-key", idempotencyKey).post(core.json(deletionRequestBody(sourceIds).toString()))
        return try {
            val (call, response) = core.call(request) ?: return PrepareResult.SessionExpired
            response.use {
                val text = core.text(call, it)
                when {
                    it.isSuccessful -> PrepareResult.Prepared(parsePreparedReview(core.data(text)))
                    it.code == 401 -> PrepareResult.SessionExpired
                    else -> core.error(text).let { e -> if (core.inconclusive(it.code, e.code)) PrepareResult.NoAnswer(e.code) else PrepareResult.Refused(e.code) }
                }
            }
        } catch (_: IOException) {
            PrepareResult.NoAnswer("network")
        } catch (_: IllegalArgumentException) {
            // Includes a body that is not the contract's shape: the review may exist, and the same key replays it.
            PrepareResult.NoAnswer("malformed_response")
        }
    }

    override suspend fun confirm(reviewId: String, idempotencyKey: String, paramsDigest: String): ConfirmResult {
        if (!isUuid(reviewId) || !isSha256Hex(paramsDigest)) return ConfirmResult.Rejected("invalid_request", 400)
        val body = buildJsonObject { put("paramsDigest", paramsDigest) }.toString()
        val request = Request.Builder().url(core.url("reviews", reviewId, "confirm")).header("idempotency-key", idempotencyKey).post(core.json(body))
        return try {
            val (call, response) = core.call(request) ?: return ConfirmResult.SessionExpired
            response.use {
                val text = core.text(call, it)
                if (it.isSuccessful) return ConfirmResult.Confirmed(parseConfirmed(core.data(text)))
                val error = core.error(text)
                when {
                    it.code == 401 -> ConfirmResult.SessionExpired
                    // Answered before anything was read or written: the passkey session is simply not recent enough.
                    it.code == 403 && error.code == "recent_auth_required" -> ConfirmResult.RecentAuthRequired
                    it.code == 409 && error.code == "review_consumed" -> ConfirmResult.AlreadyConsumed(error.details?.get("operationId")?.jsonPrimitive?.contentOrNull?.takeIf(::isUuid))
                    // The Worker's own words that nothing was committed: the ledger could not be written yet.
                    error.code == "ledger_unavailable" -> ConfirmResult.NotCompleted(error.code)
                    core.inconclusive(it.code, error.code) -> ConfirmResult.NoAnswer(error.code)
                    else -> ConfirmResult.Rejected(error.code, it.code)
                }
            }
        } catch (_: IOException) {
            ConfirmResult.NoAnswer("network")
        } catch (_: IllegalArgumentException) {
            // A success the phone cannot read may well have committed.
            ConfirmResult.NoAnswer("malformed_response")
        }
    }

    override suspend fun readReview(reviewId: String): ReviewRead<ReviewReadback> = read(reviewId, "reviews") { parseReviewReadback(it) }

    override suspend fun readOperation(operationId: String): ReviewRead<Operation> = read(operationId, "operations") { parseOperation(it) }

    private suspend fun <T> read(id: String, collection: String, parse: (JsonElement) -> T): ReviewRead<T> {
        if (!isUuid(id)) return ReviewRead.NotKnown
        return try {
            val (call, response) = core.call(Request.Builder().url(core.url(collection, id)).get()) ?: return ReviewRead.SessionExpired
            response.use {
                val text = core.text(call, it)
                when {
                    it.isSuccessful -> ReviewRead.Ok(parse(core.data(text)))
                    it.code == 401 -> ReviewRead.SessionExpired
                    it.code == 404 || it.code == 410 -> ReviewRead.NotKnown
                    else -> ReviewRead.Unavailable(core.error(text).code)
                }
            }
        } catch (_: IOException) {
            ReviewRead.Unavailable("network")
        } catch (_: IllegalArgumentException) {
            ReviewRead.Unavailable("malformed_response")
        }
    }
}

/** Exports and attachment bytes: JSON requests plus authenticated, cancellable streams. */
class HttpExportApi(client: OkHttpClient, baseUrl: HttpUrl, tokens: AccessTokens) : ExportApi, AttachmentContentApi {
    private val core = HttpCore(client, baseUrl, tokens)

    override suspend fun create(idempotencyKey: String, scope: ExportScope): CreateExportResult {
        if (scope.problem() != null) return CreateExportResult.Refused("invalid_request")
        val request = Request.Builder().url(core.url("exports")).header("idempotency-key", idempotencyKey).post(core.json(createExportBody(scope).toString()))
        return try {
            val (call, response) = core.call(request) ?: return CreateExportResult.SessionExpired
            response.use {
                val text = core.text(call, it)
                when {
                    it.isSuccessful -> CreateExportResult.Created(parseExportStatus(core.data(text)))
                    it.code == 401 -> CreateExportResult.SessionExpired
                    else -> core.error(text).let { e -> if (core.inconclusive(it.code, e.code)) CreateExportResult.NoAnswer(e.code) else CreateExportResult.Refused(e.code) }
                }
            }
        } catch (_: IOException) {
            CreateExportResult.NoAnswer("network")
        } catch (_: IllegalArgumentException) {
            CreateExportResult.NoAnswer("malformed_response")
        }
    }

    override suspend fun status(exportId: String): ExportRead {
        if (!isUuid(exportId)) return ExportRead.NotKnown
        return try {
            val (call, response) = core.call(Request.Builder().url(core.url("exports", exportId)).get()) ?: return ExportRead.SessionExpired
            response.use {
                val text = core.text(call, it)
                when {
                    it.isSuccessful -> ExportRead.Ok(parseExportStatus(core.data(text)))
                    it.code == 401 -> ExportRead.SessionExpired
                    it.code == 404 -> ExportRead.NotKnown
                    // A ready export whose manifest the Worker cannot read is answered with a fixed code, never with empty counts.
                    else -> ExportRead.Unavailable(core.error(text).code)
                }
            }
        } catch (_: IOException) {
            ExportRead.Unavailable("network")
        } catch (_: IllegalArgumentException) {
            ExportRead.Unavailable("malformed_response")
        }
    }

    override suspend fun openContent(exportId: String): OpenResult =
        if (!isUuid(exportId)) OpenResult.Gone("not_found", 404) else stream(arrayOf("exports", exportId, "content"), checksum = false)

    override suspend fun openAttachment(attachmentId: String): OpenResult =
        if (!isUuid(attachmentId)) OpenResult.Gone("not_found", 404) else stream(arrayOf("attachments", attachmentId, "content"), checksum = true)

    private suspend fun stream(segments: Array<String>, checksum: Boolean): OpenResult {
        return try {
            val (call, response) = core.call(Request.Builder().url(core.url(*segments)).get()) ?: return OpenResult.SessionExpired
            if (response.isSuccessful) {
                val declared = if (checksum) response.header("x-attachment-sha256")?.lowercase() else null
                return OpenResult.Open(ContentStream(response.body.contentLength(), declared, ClosingStream(response), call::cancel))
            }
            response.use {
                val text = core.text(call, it)
                val error = core.error(text)
                when {
                    it.code == 401 -> OpenResult.SessionExpired
                    it.code == 404 || it.code == 410 -> OpenResult.Gone(error.code, it.code)
                    // `not_ready`: the export is still being built; later is the answer.
                    it.code == 409 && error.code == "not_ready" -> OpenResult.Unavailable(error.code)
                    core.inconclusive(it.code, error.code) || it.code == 503 -> OpenResult.Unavailable(error.code)
                    else -> OpenResult.Refused(error.code, it.code)
                }
            }
        } catch (_: IOException) {
            OpenResult.Unavailable("network")
        } catch (_: IllegalArgumentException) {
            OpenResult.Unavailable("malformed_response")
        }
    }

    /** The response body as a stream whose close also closes the response. */
    private class ClosingStream(private val response: Response) : java.io.FilterInputStream(response.body.byteStream()) {
        override fun close() {
            try {
                super.close()
            } finally {
                response.close()
            }
        }
    }
}
