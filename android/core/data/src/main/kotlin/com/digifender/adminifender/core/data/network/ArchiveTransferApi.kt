package com.digifender.adminifender.core.data.network

import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Unavailable mutations may have committed. Retrying them must preserve the exact key and body. */
sealed interface TransferResult<out T> {
    data class Ok<T>(val value: T) : TransferResult<T>
    data class Rejected(val code: String, val operationId: String? = null) : TransferResult<Nothing>
    data class Unavailable(val code: String) : TransferResult<Nothing>
    data object SignIn : TransferResult<Nothing>
}

@Serializable
data class DeleteParams(val sourceIds: List<String>)

@Serializable
data class DeletePreconditions(val sources: Map<String, Int>, val concerns: Map<String, Int>)

@Serializable
data class ReviewedSource(val id: String, val subject: String? = null, val receivedAt: String, val admission: String)

@Serializable
data class DeletePreview(
    val sources: List<ReviewedSource>,
    val attachments: Int,
    val concernsErased: List<String>,
    val concernsDetached: List<String>,
    val derived: List<String>,
    val expectedCount: Int,
)

@Serializable
data class DeleteReview(
    val reviewId: String,
    val effect: String,
    val params: DeleteParams,
    val paramsDigest: String,
    val preconditions: DeletePreconditions,
    val preview: DeletePreview,
    val expiresAt: String,
    val requiresRecentAuth: Boolean,
)

@Serializable
data class DeletionReceipt(val ledgerSeq: Long, val ledgerRecordHash: String, val sourcesDeleted: Int)

@Serializable
data class ConfirmedDeletion(val operationId: String, val state: String, val receipt: DeletionReceipt)

@Serializable
data class DeletionIntent(val effect: String, val params: DeleteParams)

@Serializable
data class DeletionOperation(val id: String, val reviewId: String, val state: String, val intent: DeletionIntent, val receipt: DeletionReceipt? = null)

@Serializable
data class ExportCounts(val sources: Int, val attachments: Int, val concerns: Int, val rules: Int, val services: Int)

@Serializable
data class ExportOmission(val sourceId: String, val reason: String)

@Serializable
data class ArchiveExport(
    val id: String,
    val state: String,
    val snapshotSeq: Long,
    val expiresAt: String,
    val createdAt: String? = null,
    val counts: ExportCounts? = null,
    val omissions: List<ExportOmission>? = null,
)

interface ReviewApi {
    suspend fun reviewDeletion(sourceIds: List<String>, key: String): TransferResult<DeleteReview>
    suspend fun confirmDeletion(reviewId: String, digest: String, key: String): TransferResult<ConfirmedDeletion>
    suspend fun operation(id: String): TransferResult<DeletionOperation>
}

interface ExportApi {
    suspend fun createExport(key: String): TransferResult<ArchiveExport>
    suspend fun export(id: String): TransferResult<ArchiveExport>
}

interface ArchiveBytesApi {
    suspend fun attachment(id: String, hash: String, size: Long, output: OutputStream): TransferResult<Long>
    suspend fun exportContent(id: String, output: OutputStream): TransferResult<Long>
}

const val MAX_ATTACHMENT_BYTES = 50L * 1024 * 1024
const val MAX_EXPORT_BYTES = 512L * 1024 * 1024

/** Private, authenticated endpoints only: a redirect can never turn mail content into an arbitrary fetch. */
class HttpArchiveTransferApi(client: OkHttpClient, private val baseUrl: HttpUrl, private val tokens: AccessTokens) : ReviewApi, ExportApi, ArchiveBytesApi {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun url(path: String) = baseUrl.newBuilder().addPathSegments("api/v1").addPathSegments(path).build()

    override suspend fun reviewDeletion(sourceIds: List<String>, key: String) = request("reviews", key, buildJsonObject {
        put("effect", "archive.delete")
        put("params", wireJson.encodeToJsonElement(DeleteParams.serializer(), DeleteParams(sourceIds)))
    }, DeleteReview.serializer())

    override suspend fun confirmDeletion(reviewId: String, digest: String, key: String): TransferResult<ConfirmedDeletion> {
        if (!isSourceId(reviewId) || !HASH.matches(digest)) return TransferResult.Rejected("invalid_request")
        return request("reviews/$reviewId/confirm", key, buildJsonObject { put("paramsDigest", digest) }, ConfirmedDeletion.serializer())
    }

    override suspend fun operation(id: String): TransferResult<DeletionOperation> = if (isSourceId(id)) request("operations/$id", null, null, DeletionOperation.serializer()) else TransferResult.Rejected("invalid_request")
    override suspend fun createExport(key: String) = request("exports", key, JsonObject(emptyMap()), ArchiveExport.serializer())
    override suspend fun export(id: String): TransferResult<ArchiveExport> = if (isSourceId(id)) request("exports/$id", null, null, ArchiveExport.serializer()) else TransferResult.Rejected("invalid_request")

    private suspend fun <T> request(path: String, key: String?, body: JsonObject?, serializer: KSerializer<T>): TransferResult<T> {
        val token = tokens.current() ?: return TransferResult.SignIn
        return try {
            withContext(Dispatchers.IO) {
                val builder = Request.Builder().url(url(path)).header("authorization", "Bearer $token")
                if (body != null) builder.header("idempotency-key", requireNotNull(key)).post(body.toString().toRequestBody(jsonType))
                http.newCall(builder.build()).execute().use { response ->
                    if (response.code == 401) {
                        tokens.rejected(token)
                        return@withContext TransferResult.SignIn
                    }
                    val source = response.body.source()
                    if (response.body.contentLength() > MAX_RESPONSE_BYTES || source.request(MAX_RESPONSE_BYTES + 1)) return@withContext TransferResult.Unavailable("response_too_large")
                    val text = source.readUtf8()
                    if (response.isSuccessful) TransferResult.Ok(wireJson.decodeFromJsonElement(serializer, wireJson.parseToJsonElement(text).jsonObject.getValue("data")))
                    else failure(response, text)
                }
            }
        } catch (_: IOException) {
            TransferResult.Unavailable("network")
        } catch (_: IllegalArgumentException) {
            TransferResult.Unavailable("malformed_response")
        } catch (_: NoSuchElementException) {
            TransferResult.Unavailable("malformed_response")
        }
    }

    override suspend fun attachment(id: String, hash: String, size: Long, output: OutputStream): TransferResult<Long> {
        if (!isSourceId(id) || !HASH.matches(hash) || size !in 0..MAX_ATTACHMENT_BYTES) return TransferResult.Rejected("invalid_attachment")
        return bytes("attachments/$id/content", output, MAX_ATTACHMENT_BYTES, hash, size)
    }

    override suspend fun exportContent(id: String, output: OutputStream): TransferResult<Long> = if (isSourceId(id)) bytes("exports/$id/content", output, MAX_EXPORT_BYTES) else TransferResult.Rejected("invalid_request")

    private suspend fun bytes(path: String, output: OutputStream, limit: Long, hash: String? = null, size: Long? = null): TransferResult<Long> {
        val token = tokens.current() ?: return TransferResult.SignIn
        return try {
            withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(url(path)).header("authorization", "Bearer $token").build()).execute().use { response ->
                    if (response.code == 401) {
                        tokens.rejected(token)
                        return@withContext TransferResult.SignIn
                    }
                    if (!response.isSuccessful) {
                        val source = response.body.source()
                        if (source.request(MAX_RESPONSE_BYTES + 1)) return@withContext TransferResult.Unavailable("response_too_large")
                        return@withContext failure(response, source.readUtf8())
                    }
                    val length = response.body.contentLength()
                    if (length > limit) return@withContext TransferResult.Rejected("file_too_large")
                    if (size != null && (length != size || response.header("x-attachment-sha256") != hash)) return@withContext TransferResult.Rejected("integrity_mismatch")
                    if (!response.header("content-disposition").orEmpty().startsWith("attachment;") || response.header("x-content-type-options") != "nosniff") return@withContext TransferResult.Rejected("unsafe_content")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    val buffer = ByteArray(32 * 1024)
                    response.body.byteStream().use { input ->
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > limit || (size != null && total > size)) return@withContext TransferResult.Rejected("file_too_large")
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                    if ((length >= 0 && total != length) || (size != null && total != size) || (hash != null && digest.digest().hex() != hash)) TransferResult.Rejected("integrity_mismatch") else TransferResult.Ok(total)
                }
            }
        } catch (_: IOException) {
            TransferResult.Unavailable("network")
        }
    }

    private fun failure(response: Response, text: String): TransferResult<Nothing> {
        val error = runCatching { wireJson.parseToJsonElement(text).jsonObject["error"]?.jsonObject }.getOrNull()
        val code = runCatching { error?.get("code")?.jsonPrimitive?.content }.getOrNull() ?: "unreadable_error"
        val operation = runCatching { error?.get("details")?.jsonObject?.get("operationId")?.jsonPrimitive?.content }.getOrNull()
        return if (response.code >= 500 || response.code == 429 || code in setOf("request_in_progress", "idempotency_conflict")) TransferResult.Unavailable(code) else TransferResult.Rejected(code, operation)
    }
}

internal val HASH = Regex("^[0-9a-f]{64}$")
internal fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

/** Narrow an already unsuccessful result without carrying the successful value's type into another operation. */
internal fun transferFailure(result: TransferResult<*>): TransferResult<Nothing> = when (result) {
    is TransferResult.Rejected -> result
    is TransferResult.Unavailable -> result
    TransferResult.SignIn -> TransferResult.SignIn
    is TransferResult.Ok -> error("Expected an unsuccessful transfer")
}
