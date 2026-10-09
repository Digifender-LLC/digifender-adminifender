package com.digifender.adminifender.core.data.network

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The most sources one deletion review may name (the Worker rejects more; the phone never sends a first page of a larger selection). */
const val MAX_DELETE_SOURCES = 50

enum class ReviewEffect(val wire: String) {
    ARCHIVE_DELETE("archive.delete"),
    DEVICE_REVOKE("device.revoke"),
    ;

    companion object {
        val byWire = entries.associateBy { it.wire }
    }
}

/** What the Worker read for one source when it prepared the review. The subject is shown now and never stored. */
data class DeletionPreviewSource(val id: String, val subject: String?, val receivedAtMs: Long, val quarantined: Boolean)

/** The exact loss the review shows: sources, files, concerns and derived data (lld.md#deletion-and-privacy-propagation). */
data class DeletionPreview(
    val sources: List<DeletionPreviewSource>,
    val attachments: Int,
    val concernsErased: List<String>,
    val concernsDetached: List<String>,
    val derived: List<String>,
    val expectedCount: Int,
)

/** A prepared `archive.delete` review. [paramsDigest] binds the confirmation to these sources at these revisions. */
data class PreparedDeletion(
    val reviewId: String,
    val sourceIds: List<String>,
    val paramsDigest: String,
    val sourceRevisions: Map<String, Int>,
    val concernRevisions: Map<String, Int>,
    val preview: DeletionPreview,
    val expiresAtMs: Long,
)

/** A prepared device revocation. The app never asks for one in this slice; it is parsed so the contract is held in full. */
data class PreparedRevocation(val reviewId: String, val deviceId: String, val paramsDigest: String, val expiresAtMs: Long)

sealed interface PreparedReview {
    data class Deletion(val review: PreparedDeletion) : PreparedReview

    data class Revocation(val review: PreparedRevocation) : PreparedReview
}

enum class ReviewState(val wire: String) {
    OPEN("open"),
    EXPIRED("expired"),
    CONSUMED("consumed"),
    ;

    companion object {
        val byWire = entries.associateBy { it.wire }
    }
}

enum class OperationState(val wire: String) {
    PREPARED("prepared"),
    DISPATCHING("dispatching"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    UNCERTAIN("uncertain"),
    CANCELLED("cancelled"),
    ;

    companion object {
        val byWire = entries.associateBy { it.wire }
    }
}

/** Content-free recovery state of a review (GET `/reviews/{id}`). It carries no parameters, preview or subject. */
data class ReviewReadback(
    val reviewId: String,
    val effect: ReviewEffect,
    val paramsDigest: String,
    val state: ReviewState,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val consumedOperationId: String?,
    val operationState: OperationState?,
)

sealed interface OperationReceipt {
    /** Hidden, tombstoned and recorded in the independent ledger. It does not say cleanup finished, nor anything about backups. */
    data class Deletion(val ledgerSeq: Long, val ledgerRecordHash: String, val sourcesDeleted: Int) : OperationReceipt

    data class Revocation(val deviceId: String) : OperationReceipt
}

enum class CleanupState(val wire: String) {
    PENDING("pending"),
    PARTIAL("partial"),
    COMPLETE("complete"),
    FAILED("failed"),
    UNKNOWN("unknown"),
    ;

    companion object {
        val byWire = entries.associateBy { it.wire }
    }
}

/**
 * Asynchronous removal of live archive content. The scope is always the live archive: export files and backups are not
 * counted here, so [CleanupState.COMPLETE] never means every byte is gone.
 */
data class Cleanup(val state: CleanupState, val total: Int, val completed: Int, val failed: Int, val unrecorded: Int)

data class Operation(
    val id: String,
    val reviewId: String,
    val state: OperationState,
    val effect: ReviewEffect,
    val sourceIds: List<String>,
    val deviceId: String?,
    val receipt: OperationReceipt?,
    val errorCode: String?,
    val createdAtMs: Long,
    val settledAtMs: Long?,
    val cleanup: Cleanup?,
)

data class ConfirmedReview(val operationId: String, val receipt: OperationReceipt)

/** Result of a read the owner may repeat: a parsed value, the end of the session, or no usable answer. */
sealed interface ReviewRead<out T> {
    data class Ok<T>(val value: T) : ReviewRead<T>

    data object SessionExpired : ReviewRead<Nothing>

    /** 404 or 410: only "not known to the server". It is never evidence that an effect did not happen. */
    data object NotKnown : ReviewRead<Nothing>

    data class Unavailable(val code: String) : ReviewRead<Nothing>
}

sealed interface PrepareResult {
    data class Prepared(val review: PreparedReview) : PrepareResult

    data object SessionExpired : PrepareResult

    /** The Worker refused the request itself; nothing was prepared. */
    data class Refused(val code: String) : PrepareResult

    /** No usable answer. The same Idempotency-Key may be sent again; it replays the same review. */
    data class NoAnswer(val code: String) : PrepareResult
}

sealed interface ConfirmResult {
    data class Confirmed(val confirmed: ConfirmedReview) : ConfirmResult

    /** 409 `review_consumed`: the review was already used, by this request or an earlier copy of it. Read the operation. */
    data class AlreadyConsumed(val operationId: String?) : ConfirmResult

    /** The Worker answered that this confirmation did nothing: expired, changed, mismatched or refused. */
    data class Rejected(val code: String, val status: Int) : ConfirmResult

    /** The Worker said, with its own code, that nothing was committed and the same request may be repeated. */
    data class NotCompleted(val code: String) : ConfirmResult

    /** 403 `recent_auth_required`, answered before anything was read: confirm with the passkey again. */
    data object RecentAuthRequired : ConfirmResult

    data object SessionExpired : ConfirmResult

    /** No usable answer. The request may have committed, so the outcome is unknown until it is read back. */
    data class NoAnswer(val code: String) : ConfirmResult
}

/** `POST /reviews`, `POST /reviews/{id}/confirm`, `GET /reviews/{id}` and `GET /operations/{id}` (lld.md#reviews-external-effects-and-readback). */
interface ReviewApi {
    suspend fun prepareDeletion(idempotencyKey: String, sourceIds: List<String>): PrepareResult

    suspend fun confirm(reviewId: String, idempotencyKey: String, paramsDigest: String): ConfirmResult

    suspend fun readReview(reviewId: String): ReviewRead<ReviewReadback>

    suspend fun readOperation(operationId: String): ReviewRead<Operation>
}

// -- Strict parsers over the shared schemas (contracts/schema/v1/review-*.json, operation.json) ----------------------------------

private fun parseDeletionIntent(element: JsonElement, path: String): List<String> {
    val intent = element.asObject(path).exactly(path, setOf("effect", "params"))
    if (intent.text("effect", path) != ReviewEffect.ARCHIVE_DELETE.wire) throw WireException("$path.effect: expected archive.delete")
    val params = intent.obj("params", path).exactly("$path.params", setOf("sourceIds"))
    return params.array("sourceIds", "$path.params").uuids("$path.params.sourceIds", 1, MAX_DELETE_SOURCES)
}

fun parsePreparedReview(element: JsonElement): PreparedReview {
    val path = "$"
    val body = element.asObject(path).exactly(path, setOf("reviewId", "effect", "params", "paramsDigest", "preconditions", "preview", "expiresAt", "requiresRecentAuth"))
    val reviewId = body.uuid("reviewId", path)
    val effect = body.choice("effect", path, ReviewEffect.byWire)
    val digest = body.digest("paramsDigest", path)
    val expiresAt = body.timestamp("expiresAt", path)
    if (!body.bool("requiresRecentAuth", path)) throw WireException("$.requiresRecentAuth: must be true")
    val params = body.obj("params", path)
    val preconditions = body.obj("preconditions", path)
    val preview = body.obj("preview", path)
    return when (effect) {
        ReviewEffect.ARCHIVE_DELETE -> {
            params.exactly("$.params", setOf("sourceIds"))
            val sourceIds = params.array("sourceIds", "$.params").uuids("$.params.sourceIds", 1, MAX_DELETE_SOURCES)
            preconditions.exactly("$.preconditions", setOf("sources", "concerns"))
            val sources = preconditions.obj("sources", "$.preconditions").revisions("$.preconditions.sources")
            val concerns = preconditions.obj("concerns", "$.preconditions").revisions("$.preconditions.concerns")
            preview.exactly("$.preview", setOf("sources", "attachments", "concernsErased", "concernsDetached", "derived", "expectedCount"))
            val previewSources = preview.array("sources", "$.preview").mapIndexed { i, item ->
                val p = "$.preview.sources[$i]"
                val source = item.asObject(p).exactly(p, setOf("id", "subject", "receivedAt", "admission"))
                val admission = source.choice("admission", p, mapOf("normal" to false, "quarantine" to true))
                DeletionPreviewSource(source.uuid("id", p), source.nullableText("subject", p), source.timestamp("receivedAt", p), admission)
            }
            val derived = preview.array("derived", "$.preview").mapIndexed { i, item ->
                (item as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: throw WireException("$.preview.derived[$i]: expected a string")
            }
            PreparedReview.Deletion(
                PreparedDeletion(
                    reviewId,
                    sourceIds,
                    digest,
                    sources,
                    concerns,
                    DeletionPreview(
                        previewSources,
                        preview.int("attachments", "$.preview", 0),
                        preview.array("concernsErased", "$.preview").uuids("$.preview.concernsErased"),
                        preview.array("concernsDetached", "$.preview").uuids("$.preview.concernsDetached"),
                        derived,
                        preview.int("expectedCount", "$.preview", 1),
                    ),
                    expiresAt,
                ),
            )
        }
        ReviewEffect.DEVICE_REVOKE -> {
            params.exactly("$.params", setOf("deviceId"))
            val deviceId = params.uuid("deviceId", "$.params")
            preconditions.exactly("$.preconditions", setOf("devices")).obj("devices", "$.preconditions").revisions("$.preconditions.devices")
            preview.exactly("$.preview", setOf("device", "current", "sessionsRevoked", "expectedCount"))
            preview.obj("device", "$.preview").exactly("$.preview.device", setOf("id", "name")).also {
                it.uuid("id", "$.preview.device")
                it.text("name", "$.preview.device")
            }
            preview.bool("current", "$.preview")
            preview.int("sessionsRevoked", "$.preview", 0)
            if (preview.int("expectedCount", "$.preview") != 1) throw WireException("$.preview.expectedCount: must be 1")
            PreparedReview.Revocation(PreparedRevocation(reviewId, deviceId, digest, expiresAt))
        }
    }
}

fun parseReviewReadback(element: JsonElement): ReviewReadback {
    val path = "$"
    val body = element.asObject(path).exactly(path, setOf("reviewId", "effect", "paramsDigest", "state", "createdAt", "expiresAt", "requiresRecentAuth", "consumedOperationId", "operationState"))
    if (!body.bool("requiresRecentAuth", path)) throw WireException("$.requiresRecentAuth: must be true")
    val state = body.choice("state", path, ReviewState.byWire)
    val operationId = body.nullableUuid("consumedOperationId", path)
    val operationState = if (body.field("operationState", path) is kotlinx.serialization.json.JsonNull) null else body.choice("operationState", path, OperationState.byWire)
    if (state == ReviewState.CONSUMED) {
        if (operationId == null || operationState == null) throw WireException("$: a consumed review names its operation")
    } else if (operationId != null || operationState != null) {
        throw WireException("$: only a consumed review has an operation")
    }
    return ReviewReadback(
        body.uuid("reviewId", path),
        body.choice("effect", path, ReviewEffect.byWire),
        body.digest("paramsDigest", path),
        state,
        body.timestamp("createdAt", path),
        body.timestamp("expiresAt", path),
        operationId,
        operationState,
    )
}

private fun parseReceipt(element: JsonElement, path: String): OperationReceipt {
    val body = element.asObject(path)
    return if ("deviceRevoked" in body) {
        OperationReceipt.Revocation(body.exactly(path, setOf("deviceRevoked")).uuid("deviceRevoked", path))
    } else {
        body.exactly(path, setOf("ledgerSeq", "ledgerRecordHash", "sourcesDeleted"))
        OperationReceipt.Deletion(body.long("ledgerSeq", path, 1), body.digest("ledgerRecordHash", path), body.int("sourcesDeleted", path, 1))
    }
}

private fun parseCleanup(element: JsonElement, path: String): Cleanup {
    val body = element.asObject(path).exactly(path, setOf("scope", "state", "total", "completed", "failed", "unrecorded"))
    // Export files and backups are not part of this count; any other scope is something this phone cannot describe.
    if (body.text("scope", path) != "live_archive") throw WireException("$path.scope: expected live_archive")
    val cleanup = Cleanup(body.choice("state", path, CleanupState.byWire), body.int("total", path, 1), body.int("completed", path, 0), body.int("failed", path, 0), body.int("unrecorded", path, 0))
    val consistent = when (cleanup.state) {
        CleanupState.COMPLETE -> cleanup.failed == 0 && cleanup.unrecorded == 0
        CleanupState.UNKNOWN -> cleanup.unrecorded >= 1
        CleanupState.FAILED -> cleanup.failed >= 1 && cleanup.unrecorded == 0
        CleanupState.PENDING -> cleanup.failed == 0 && cleanup.unrecorded == 0 && cleanup.completed == 0
        CleanupState.PARTIAL -> cleanup.failed == 0 && cleanup.unrecorded == 0
    }
    if (!consistent) throw WireException("$path: counts contradict the state")
    return cleanup
}

fun parseOperation(element: JsonElement): Operation {
    val path = "$"
    val body = element.asObject(path).exactly(path, setOf("id", "reviewId", "state", "intent", "receipt", "errorCode", "createdAt", "settledAt", "cleanup"))
    val intent = body.obj("intent", path)
    val effect = intent.choice("effect", "$.intent", ReviewEffect.byWire)
    val sourceIds: List<String>
    val deviceId: String?
    if (effect == ReviewEffect.ARCHIVE_DELETE) {
        sourceIds = parseDeletionIntent(intent, "$.intent")
        deviceId = null
    } else {
        intent.exactly("$.intent", setOf("effect", "params")).obj("params", "$.intent").exactly("$.intent.params", setOf("deviceId"))
        sourceIds = emptyList()
        deviceId = intent.obj("params", "$.intent").uuid("deviceId", "$.intent.params")
    }
    val cleanupElement = body.field("cleanup", path)
    // Only a deletion has a cleanup to report; for any other effect it is null.
    val cleanup = if (cleanupElement is kotlinx.serialization.json.JsonNull) null else parseCleanup(cleanupElement, "$.cleanup")
    if ((effect == ReviewEffect.ARCHIVE_DELETE) != (cleanup != null)) throw WireException("$.cleanup: present exactly for archive.delete")
    val receiptElement = body.field("receipt", path)
    return Operation(
        body.uuid("id", path),
        body.uuid("reviewId", path),
        body.choice("state", path, OperationState.byWire),
        effect,
        sourceIds,
        deviceId,
        if (receiptElement is kotlinx.serialization.json.JsonNull) null else parseReceipt(receiptElement, "$.receipt"),
        body.nullableCode("errorCode", path),
        body.timestamp("createdAt", path),
        body.nullableTimestamp("settledAt", path),
        cleanup,
    )
}

fun parseConfirmed(element: JsonElement): ConfirmedReview {
    val body = element.asObject("$").exactly("$", setOf("operationId", "state", "receipt"))
    if (body.text("state", "$") != OperationState.SUCCEEDED.wire) throw WireException("$.state: native effects settle as succeeded")
    return ConfirmedReview(body.uuid("operationId", "$"), parseReceipt(body.field("receipt", "$"), "$.receipt"))
}

/** The request body for `POST /reviews` with an `archive.delete` effect: the IDs sorted, so the same selection is the same request. */
fun deletionRequestBody(sourceIds: List<String>): JsonObject = kotlinx.serialization.json.buildJsonObject {
    put("effect", kotlinx.serialization.json.JsonPrimitive(ReviewEffect.ARCHIVE_DELETE.wire))
    put("params", kotlinx.serialization.json.buildJsonObject { put("sourceIds", kotlinx.serialization.json.JsonArray(sourceIds.sorted().map { kotlinx.serialization.json.JsonPrimitive(it) })) })
}

sealed interface ReviewRequest {
    data class Deletion(val sourceIds: List<String>) : ReviewRequest

    data class Revocation(val deviceId: String) : ReviewRequest
}

/** `review-request.schema.json`: the body of `POST /reviews`. The phone only sends deletions; this reads both so the contract is held in full. */
fun parseReviewRequest(element: JsonElement): ReviewRequest {
    val body = element.asObject("$").exactly("$", setOf("effect", "params"))
    return when (body.choice("effect", "$", ReviewEffect.byWire)) {
        ReviewEffect.ARCHIVE_DELETE -> ReviewRequest.Deletion(parseDeletionIntent(body, "$"))
        ReviewEffect.DEVICE_REVOKE -> ReviewRequest.Revocation(body.obj("params", "$").exactly("$.params", setOf("deviceId")).uuid("deviceId", "$.params"))
    }
}
