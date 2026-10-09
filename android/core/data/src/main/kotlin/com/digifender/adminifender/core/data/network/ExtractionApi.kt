package com.digifender.adminifender.core.data.network

import java.io.IOException
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

// Device extraction of PDFs and images (lld.md#text-extraction-and-ocr). The limits below are the Worker's; the claim
// repeats them and a claim that states other limits is refused, so a changed Worker cannot silently change what is sent.
const val MAX_EXTRACTION_PAGES = 100
const val MAX_BATCH_PAGES = 10
const val MAX_BATCH_BYTES = 128 * 1024
private const val MAX_BATCHES = 100
private const val MAX_PART = 999
private const val MAX_ATTACHMENT_BYTES = 26_214_400L
private val EXTRACTOR_VERSION = Regex("^[a-z0-9][a-z0-9._-]*$")

enum class DeviceKind(val wire: String) {
    PDF("pdf"),
    IMAGE("image"),
    ;

    companion object {
        val byWire = entries.associateBy { it.wire }
    }
}

/** `pdf_text` is a native text layer; `ocr` is recognition and is allowed only when the claim says `ocrAllowed`. */
enum class PageMethod(val wire: String) {
    PDF_TEXT("pdf_text"),
    OCR("ocr"),
    ;

    companion object {
        val byWire = entries.associateBy { it.wire }
    }
}

enum class NoWorkReason(val wire: String) {
    NO_WORK("no_work"),
    BUSY("busy"),
    NO_CAPABILITY("no_capability"),
    ;

    companion object {
        val byWire = entries.associateBy { it.wire }
    }
}

/** What the phone gives back when it cannot extract a file; [UNAVAILABLE] returns the file to the queue. */
enum class ExtractionOutcome(val wire: String) {
    ENCRYPTED("encrypted"),
    UNSUPPORTED("unsupported"),
    FAILED("failed"),
    UNAVAILABLE("unavailable"),
    ;

    companion object {
        val byWire = entries.associateBy { it.wire }
    }
}

/** One leased file (`extraction-claim.schema.json`). The checkpoint says where an earlier lease of the same generation stopped. */
data class ExtractionWork(
    val leaseId: String,
    val leaseToken: String,
    val attachmentId: String,
    val sourceId: String,
    val kind: DeviceKind,
    val contentHash: String,
    val sizeBytes: Long,
    val extractorVersion: String,
    val policyRevision: Long,
    val generationId: String,
    val ocrAllowed: Boolean,
    val nextBatchOrdinal: Int,
    val nextPage: Int,
    /** The server's hash of each batch already stored, in order; one per [nextBatchOrdinal]. */
    val batchHashes: List<String>,
    val expiresAtMs: Long,
)

sealed interface ClaimResponse {
    data class Work(val work: ExtractionWork) : ClaimResponse

    data class None(val reason: NoWorkReason) : ClaimResponse
}

data class PageText(val page: Int, val part: Int, val method: PageMethod, val text: String, val confidence: Double? = null)

/**
 * One immutable batch. [lastPage] counts pages with no text too. Only the final batch is [complete]; it carries
 * [manifestHash], the SHA-256 of the earlier batches' server hashes joined by newlines, and [totalPages] when the document is longer than was read.
 */
data class ExtractionBatch(
    val ordinal: Int,
    val pages: List<PageText>,
    val lastPage: Int,
    val complete: Boolean,
    val totalPages: Int? = null,
    val manifestHash: String? = null,
)

enum class BatchStatus(val wire: String) {
    ACCEPTED("accepted"),
    REPLAYED("replayed"),
    ;

    companion object {
        val byWire = entries.associateBy { it.wire }
    }
}

data class BatchResult(
    val leaseId: String,
    val batchOrdinal: Int,
    val status: BatchStatus,
    val batchHash: String,
    val nextBatchOrdinal: Int,
    val nextPage: Int,
    /** True once the final batch committed the generation (also on a repeat of that batch). */
    val published: Boolean,
    /** Set only on the answer that published: the document had more pages than were read, so only the first ones are searchable. */
    val limited: Boolean,
)

// -- requests ------------------------------------------------------------------------------------------------------------

data class ClaimRequest(val extractorVersion: String, val pdfText: Boolean, val ocr: Boolean)

data class BatchRequest(
    val leaseToken: String,
    val contentHash: String,
    val extractorVersion: String,
    val policyRevision: Long,
    val generationId: String,
    val batch: ExtractionBatch,
)

data class OutcomeRequest(val leaseToken: String, val outcome: ExtractionOutcome)

fun claimRequestBody(request: ClaimRequest): JsonObject = buildJsonObject {
    put("extractorVersion", request.extractorVersion)
    put("pdfText", request.pdfText)
    put("ocr", request.ocr)
}

fun batchRequestBody(work: ExtractionWork, batch: ExtractionBatch): JsonObject = buildJsonObject {
    put("leaseToken", work.leaseToken)
    put("contentHash", work.contentHash)
    put("extractorVersion", work.extractorVersion)
    put("policyRevision", work.policyRevision)
    put("generationId", work.generationId)
    put("batchOrdinal", batch.ordinal)
    put(
        "pages",
        JsonArray(
            batch.pages.map {
                buildJsonObject {
                    put("page", it.page)
                    put("part", it.part)
                    put("method", it.method.wire)
                    put("text", it.text)
                    it.confidence?.let { c -> put("confidence", c) }
                }
            },
        ),
    )
    put("lastPage", batch.lastPage)
    put("complete", batch.complete)
    batch.totalPages?.let { put("totalPages", it) }
    batch.manifestHash?.let { put("manifestHash", it) }
}

fun outcomeRequestBody(work: ExtractionWork, outcome: ExtractionOutcome): JsonObject = buildJsonObject {
    put("leaseToken", work.leaseToken)
    put("outcome", outcome.wire)
}

private fun JsonObject.number(key: String, path: String, min: Double, max: Double): Double {
    val primitive = field(key, path) as? JsonPrimitive
    val value = primitive?.takeIf { !it.isString }?.doubleOrNull ?: throw WireException("$path.$key: expected a number")
    if (value < min || value > max) throw WireException("$path.$key: out of range")
    return value
}

private fun JsonObject.ranged(key: String, path: String, min: Long, max: Long): Long {
    val value = long(key, path, min)
    if (value > max) throw WireException("$path.$key: above $max")
    return value
}

private fun JsonObject.version(key: String, path: String) = text(key, path, EXTRACTOR_VERSION, 64).also { if (it.length < 3) throw WireException("$path.$key: too short") }

private fun JsonObject.generation(path: String) = text("generationId", path, max = 200).also { if (it.isEmpty()) throw WireException("$path.generationId: empty") }

/** `extraction-claim-request.schema.json`. */
fun parseClaimRequest(element: JsonElement): ClaimRequest {
    val body = element.asObject("$").exactly("$", setOf("extractorVersion", "pdfText", "ocr"))
    return ClaimRequest(body.version("extractorVersion", "$"), body.bool("pdfText", "$"), body.bool("ocr", "$"))
}

/** `extraction-batch.schema.json`, including the rules between fields the schema states with if/then/else. */
fun parseBatchRequest(element: JsonElement): BatchRequest {
    val path = "$"
    val body = element.asObject(path).exactly(
        path,
        setOf("leaseToken", "contentHash", "extractorVersion", "policyRevision", "generationId", "batchOrdinal", "pages", "lastPage", "complete"),
        setOf("totalPages", "manifestHash"),
    )
    val pagesArray = body.array("pages", path)
    if (pagesArray.size > 200) throw WireException("$.pages: too many entries")
    val pages = pagesArray.mapIndexed { i, item ->
        val p = "$.pages[$i]"
        val entry = item.asObject(p).exactly(p, setOf("page", "part", "method", "text"), setOf("confidence"))
        val text = entry.text("text", p)
        if (text.isEmpty() || text.codePointCount(0, text.length) > MAX_BATCH_BYTES) throw WireException("$p.text: wrong length")
        PageText(
            entry.ranged("page", p, 1, MAX_EXTRACTION_PAGES.toLong()).toInt(),
            entry.ranged("part", p, 0, MAX_PART.toLong()).toInt(),
            entry.choice("method", p, PageMethod.byWire),
            text,
            if ("confidence" in entry) entry.number("confidence", p, 0.0, 1.0) else null,
        )
    }
    val complete = body.bool("complete", path)
    val manifest = if ("manifestHash" in body) body.digest("manifestHash", path) else null
    val totalPages = if ("totalPages" in body) body.ranged("totalPages", path, 1, 100_000).toInt() else null
    if (complete && manifest == null) throw WireException("$.manifestHash: required on the final batch")
    if (!complete && (manifest != null || totalPages != null)) throw WireException("$: manifestHash and totalPages belong to the final batch")
    return BatchRequest(
        body.uuid("leaseToken", path),
        body.digest("contentHash", path),
        body.version("extractorVersion", path),
        body.long("policyRevision", path, 0),
        body.generation(path),
        ExtractionBatch(body.ranged("batchOrdinal", path, 0, MAX_BATCHES.toLong()).toInt(), pages, body.ranged("lastPage", path, 1, MAX_EXTRACTION_PAGES.toLong()).toInt(), complete, totalPages, manifest),
    )
}

/** `extraction-outcome.schema.json`. */
fun parseOutcomeRequest(element: JsonElement): OutcomeRequest {
    val body = element.asObject("$").exactly("$", setOf("leaseToken", "outcome"))
    return OutcomeRequest(body.uuid("leaseToken", "$"), body.choice("outcome", "$", ExtractionOutcome.byWire))
}

// -- responses -----------------------------------------------------------------------------------------------------------

/** `extraction-claim.schema.json`: one leased file, or `work: null` with the reason. */
fun parseClaim(element: JsonElement): ClaimResponse {
    val path = "$"
    val body = element.asObject(path)
    val workElement = body.field("work", path)
    if (workElement is JsonNull) {
        body.exactly(path, setOf("work", "reason"))
        return ClaimResponse.None(body.choice("reason", path, NoWorkReason.byWire))
    }
    body.exactly(path, setOf("work"))
    val p = "$.work"
    val work = workElement.asObject(p).exactly(
        p,
        setOf(
            "leaseId", "leaseToken", "attachmentId", "sourceId", "kind", "contentHash", "sizeBytes", "extractorVersion", "policyRevision", "generationId",
            "ocrAllowed", "maxPages", "maxBatchPages", "maxBatchBytes", "nextBatchOrdinal", "nextPage", "batchHashes", "expiresAt",
        ),
    )
    // The limits are part of the contract; a Worker that states other ones is not the Worker this version was written for.
    if (work.long("maxPages", p) != MAX_EXTRACTION_PAGES.toLong() || work.long("maxBatchPages", p) != MAX_BATCH_PAGES.toLong() || work.long("maxBatchBytes", p) != MAX_BATCH_BYTES.toLong()) {
        throw WireException("$p: limits differ from the contract")
    }
    val hashes = work.array("batchHashes", p).also { if (it.size > MAX_BATCHES) throw WireException("$p.batchHashes: too many") }.mapIndexed { i, item ->
        val primitive = item as? JsonPrimitive
        if (primitive == null || !primitive.isString || !isSha256Hex(primitive.content)) throw WireException("$p.batchHashes[$i]: expected a hash")
        primitive.content
    }
    val nextOrdinal = work.ranged("nextBatchOrdinal", p, 0, MAX_BATCHES.toLong()).toInt()
    // One server hash per batch already stored: the manifest is built from them, so a list of another length cannot be right.
    if (hashes.size != nextOrdinal) throw WireException("$p.batchHashes: expected $nextOrdinal hashes")
    return ClaimResponse.Work(
        ExtractionWork(
            work.uuid("leaseId", p),
            work.uuid("leaseToken", p),
            work.uuid("attachmentId", p),
            work.uuid("sourceId", p),
            work.choice("kind", p, DeviceKind.byWire),
            work.digest("contentHash", p),
            work.ranged("sizeBytes", p, 0, MAX_ATTACHMENT_BYTES),
            work.version("extractorVersion", p),
            work.long("policyRevision", p, 0),
            work.generation(p),
            work.bool("ocrAllowed", p),
            nextOrdinal,
            work.ranged("nextPage", p, 1, MAX_EXTRACTION_PAGES + 1L).toInt(),
            hashes,
            work.timestamp("expiresAt", p),
        ),
    )
}

/** `extraction-batch-result.schema.json`, including that `attachment` is present exactly when this answer published. */
fun parseBatchResult(element: JsonElement): BatchResult {
    val path = "$"
    val body = element.asObject(path).exactly(path, setOf("leaseId", "batchOrdinal", "status", "batchHash", "nextBatchOrdinal", "nextPage", "published"), setOf("attachment"))
    val status = body.choice("status", path, BatchStatus.byWire)
    val published = body.bool("published", path)
    val expectsAttachment = status == BatchStatus.ACCEPTED && published
    if (("attachment" in body) != expectsAttachment) throw WireException("$.attachment: present exactly when this batch published")
    var limited = false
    if (expectsAttachment) {
        val a = body.obj("attachment", path).exactly("$.attachment", setOf("state", "detail"))
        if (a.text("state", "$.attachment") != "ready") throw WireException("$.attachment.state: expected ready")
        limited = when (a.nullableText("detail", "$.attachment")) {
            null -> false
            "limit" -> true
            else -> throw WireException("$.attachment.detail: unknown value")
        }
    }
    return BatchResult(
        body.uuid("leaseId", path),
        body.ranged("batchOrdinal", path, 0, MAX_BATCHES.toLong()).toInt(),
        status,
        body.digest("batchHash", path),
        body.ranged("nextBatchOrdinal", path, 1, MAX_BATCHES + 1L).toInt(),
        body.ranged("nextPage", path, 2, MAX_EXTRACTION_PAGES + 1L).toInt(),
        published,
        limited,
    )
}

// -- the client ----------------------------------------------------------------------------------------------------------

sealed interface ClaimResult {
    data class Claimed(val work: ExtractionWork) : ClaimResult

    data class NoWork(val reason: NoWorkReason) : ClaimResult

    data object SessionExpired : ClaimResult

    /** No usable answer (network, 429, 5xx, an answer that is not the contract's shape). Nothing was leased that the phone knows of. */
    data class Unavailable(val code: String) : ClaimResult
}

sealed interface HeartbeatResult {
    data class Renewed(val expiresAtMs: Long) : HeartbeatResult

    /** The Worker no longer assigns this file to this phone. */
    data class LeaseEnded(val code: String) : HeartbeatResult

    data object SessionExpired : HeartbeatResult

    data class Unavailable(val code: String) : HeartbeatResult
}

sealed interface SubmitResult {
    data class Done(val result: BatchResult) : SubmitResult

    /** The lease, the file, the email or the policy changed under the run: stop, and claim again later. [code] is the Worker's. */
    data class LeaseEnded(val code: String) : SubmitResult

    /** The email was deleted (410). */
    data object Deleted : SubmitResult

    /** The Worker refused this batch for a reason that repeating will not change (a bug on one side). */
    data class Refused(val code: String, val status: Int) : SubmitResult

    data object SessionExpired : SubmitResult

    /** No usable answer. The same batch may be sent again: a stored batch is answered as a replay. */
    data class Unavailable(val code: String) : SubmitResult
}

sealed interface OutcomeResult {
    data object Recorded : OutcomeResult

    data class LeaseEnded(val code: String) : OutcomeResult

    data object SessionExpired : OutcomeResult

    data class Unavailable(val code: String) : OutcomeResult
}

/** The four `POST /device-work/extraction/...` calls (lld.md#text-extraction-and-ocr). */
interface ExtractionApi {
    suspend fun claim(request: ClaimRequest): ClaimResult

    suspend fun heartbeat(work: ExtractionWork): HeartbeatResult

    suspend fun submit(work: ExtractionWork, batch: ExtractionBatch): SubmitResult

    suspend fun outcome(work: ExtractionWork, outcome: ExtractionOutcome): OutcomeResult
}

/** Codes under which the Worker has ended the lease or made it unusable; the run stops and the file is claimed again later. */
private val LEASE_ENDED = setOf("lease_lost", "not_eligible", "policy_changed", "input_changed", "generation_mismatch", "generation_corrupt")

class HttpExtractionApi(client: OkHttpClient, baseUrl: HttpUrl, tokens: AccessTokens) : ExtractionApi {
    private val core = HttpCore(client, baseUrl, tokens)

    /** One POST with a fresh Idempotency-Key. Repeating a batch is safe without a stable key: the Worker answers a stored one as a replay. */
    private suspend fun <T> post(segments: Array<String>, body: JsonObject, onSession: T, onUnavailable: (String) -> T, onSuccess: (JsonElement) -> T, onFailure: (Int, ErrorInfo) -> T): T {
        val request = Request.Builder().url(core.url(*segments)).header("idempotency-key", UUID.randomUUID().toString()).post(core.json(body.toString()))
        return try {
            val (call, response) = core.call(request) ?: return onSession
            response.use {
                val text = core.text(call, it)
                when {
                    it.isSuccessful -> onSuccess(core.data(text))
                    it.code == 401 -> onSession
                    else -> core.error(text).let { e -> if (core.inconclusive(it.code, e.code)) onUnavailable(e.code) else onFailure(it.code, e) }
                }
            }
        } catch (_: IOException) {
            onUnavailable("network")
        } catch (_: IllegalArgumentException) {
            onUnavailable("malformed_response")
        }
    }

    override suspend fun claim(request: ClaimRequest): ClaimResult = post(
        arrayOf("device-work", "extraction", "claim"),
        claimRequestBody(request),
        ClaimResult.SessionExpired,
        { ClaimResult.Unavailable(it) },
        { data ->
            when (val parsed = parseClaim(data)) {
                is ClaimResponse.Work -> ClaimResult.Claimed(parsed.work)
                is ClaimResponse.None -> ClaimResult.NoWork(parsed.reason)
            }
        },
        { _, e -> ClaimResult.Unavailable(e.code) },
    )

    override suspend fun heartbeat(work: ExtractionWork): HeartbeatResult = post(
        arrayOf("device-work", "extraction", work.leaseId, "heartbeat"),
        buildJsonObject { put("leaseToken", work.leaseToken) },
        HeartbeatResult.SessionExpired,
        { HeartbeatResult.Unavailable(it) },
        { data ->
            val body = data.asObject("$").exactly("$", setOf("leaseId", "expiresAt"))
            if (body.uuid("leaseId", "$") != work.leaseId) throw WireException("$.leaseId: another lease")
            HeartbeatResult.Renewed(body.timestamp("expiresAt", "$"))
        },
        { _, e -> if (e.code in LEASE_ENDED) HeartbeatResult.LeaseEnded(e.code) else HeartbeatResult.Unavailable(e.code) },
    )

    override suspend fun submit(work: ExtractionWork, batch: ExtractionBatch): SubmitResult = post(
        arrayOf("device-work", "extraction", work.leaseId, "batch"),
        batchRequestBody(work, batch),
        SubmitResult.SessionExpired,
        { SubmitResult.Unavailable(it) },
        { data ->
            val result = parseBatchResult(data)
            // An answer about another lease or batch is not an answer to this request.
            if (result.leaseId != work.leaseId || result.batchOrdinal != batch.ordinal) throw WireException("$: answers another batch")
            SubmitResult.Done(result)
        },
        { status, e ->
            when {
                status == 410 -> SubmitResult.Deleted
                e.code in LEASE_ENDED -> SubmitResult.LeaseEnded(e.code)
                else -> SubmitResult.Refused(e.code, status)
            }
        },
    )

    override suspend fun outcome(work: ExtractionWork, outcome: ExtractionOutcome): OutcomeResult = post(
        arrayOf("device-work", "extraction", work.leaseId, "outcome"),
        outcomeRequestBody(work, outcome),
        OutcomeResult.SessionExpired,
        { OutcomeResult.Unavailable(it) },
        { data ->
            val body = data.asObject("$").exactly("$", setOf("leaseId", "attachment"))
            if (body.uuid("leaseId", "$") != work.leaseId) throw WireException("$.leaseId: another lease")
            OutcomeResult.Recorded
        },
        { status, e -> if (status == 410 || e.code in LEASE_ENDED) OutcomeResult.LeaseEnded(e.code) else OutcomeResult.Unavailable(e.code) },
    )
}
