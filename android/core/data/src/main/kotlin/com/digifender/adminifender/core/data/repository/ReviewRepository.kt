package com.digifender.adminifender.core.data.repository

import com.digifender.adminifender.core.data.network.ConfirmedDeletion
import com.digifender.adminifender.core.data.network.DeletePreconditions
import com.digifender.adminifender.core.data.network.DeleteParams
import com.digifender.adminifender.core.data.network.DeleteReview
import com.digifender.adminifender.core.data.network.DeletionReceipt
import com.digifender.adminifender.core.data.network.HASH
import com.digifender.adminifender.core.data.network.ReviewApi
import com.digifender.adminifender.core.data.network.TransferResult
import com.digifender.adminifender.core.data.network.hex
import com.digifender.adminifender.core.data.network.isSourceId
import com.digifender.adminifender.core.data.network.wireJson
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A prepared review holds one immutable request identity for every explicit confirmation retry. */
class PreparedDeletion internal constructor(val review: DeleteReview, internal val record: JournalDeletion, internal val journalGeneration: Long) {
    internal val sourceIds get() = record.sourceIds
    internal val confirmationKey get() = record.confirmationKey
    internal var submitted = record.submitted
    val needsReadback: Boolean get() = submitted
    val expiresAtMs: Long = Instant.parse(review.expiresAt).toEpochMilli()
}

/** Native archive deletion only. Provider effects and device revocation cannot pass this typed gate. */
class ReviewRepository(
    private val api: ReviewApi,
    private val clock: () -> Long,
    private val onDeleted: suspend (List<String>, Map<String, Int>) -> Unit,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
    private val journal: TransferJournal = MemoryTransferJournal(),
) {
    private val mutex = Mutex()

    suspend fun prepare(sourceIds: List<String>): TransferResult<PreparedDeletion> = mutex.withLock {
        if (sourceIds.size !in 1..50 || sourceIds.any { !isSourceId(it) } || sourceIds.distinct().size != sourceIds.size) return@withLock TransferResult.Rejected("invalid_request")
        val ids = sourceIds.sorted()
        val generation = journal.generation
        try {
            var record = journal.deletion(ids)
            if (record != null && (record.sourceIds != ids || !isSourceId(record.creationKey) || !isSourceId(record.confirmationKey))) return@withLock TransferResult.Unavailable("journal_unreadable")
            val previous = record?.review
            if (previous != null) {
                if (!validReview(previous, ids)) return@withLock TransferResult.Unavailable("journal_unreadable")
                if (record.submitted || Instant.parse(previous.expiresAt).toEpochMilli() > clock()) return@withLock TransferResult.Ok(PreparedDeletion(previous, record, generation))
                journal.removeDeletion(ids, generation)
                record = null
            }
            val draft = record ?: JournalDeletion(ids, newKey(), newKey()).also { journal.putDeletion(it, generation) }
            when (val result = api.reviewDeletion(ids, draft.creationKey)) {
                is TransferResult.Ok -> if (validReview(result.value, ids) && Instant.parse(result.value.expiresAt).toEpochMilli() > clock()) {
                    val ready = draft.copy(review = result.value)
                    journal.putDeletion(ready, generation)
                    TransferResult.Ok(PreparedDeletion(result.value, ready, generation))
                } else { journal.removeDeletion(ids, generation); TransferResult.Rejected("invalid_review") }
                is TransferResult.Rejected -> { journal.removeDeletion(ids, generation); result }
                is TransferResult.Unavailable -> result
                TransferResult.SignIn -> TransferResult.SignIn
            }
        } catch (_: IOException) {
            TransferResult.Unavailable("journal_unavailable")
        }
    }

    /** The ViewModel calls this only after a successful passkey prompt; the backend enforces its own auth clock. */
    suspend fun confirmAfterPasskey(prepared: PreparedDeletion): TransferResult<ConfirmedDeletion> = confirm(prepared, clock())

    /** Auth is checked again after the prompt; a review may expire while the passkey sheet is open. */
    suspend fun confirm(prepared: PreparedDeletion, localAuthCompletedAtMs: Long): TransferResult<ConfirmedDeletion> = mutex.withLock {
        val review = prepared.review
        val now = clock()
        if (!validReview(review, prepared.sourceIds)) return@withLock TransferResult.Rejected("invalid_review")
        if (localAuthCompletedAtMs > now || now - localAuthCompletedAtMs > RECENT_AUTH_MS) return@withLock TransferResult.Rejected("recent_auth_required")
        // After an unknown response, the retained idempotency result may still prove success after expiry.
        if (!prepared.submitted && prepared.expiresAtMs <= now) return@withLock TransferResult.Rejected("review_expired")
        try {
            journal.putDeletion(prepared.record.copy(submitted = true), prepared.journalGeneration)
        } catch (_: IOException) {
            return@withLock TransferResult.Unavailable("journal_unavailable")
        }
        prepared.submitted = true
        val result = api.confirmDeletion(review.reviewId, review.paramsDigest, prepared.confirmationKey)
        when {
            result is TransferResult.Ok -> settle(prepared, result.value)
            result is TransferResult.Rejected && result.code == "review_consumed" && result.operationId != null -> readBack(prepared, result.operationId)
            result is TransferResult.Rejected && result.code != "recent_auth_required" -> {
                try { journal.removeDeletion(prepared.sourceIds, prepared.journalGeneration) } catch (_: IOException) { return@withLock TransferResult.Unavailable("journal_unavailable") }
                result
            }
            else -> result
        }
    }

    private suspend fun readBack(prepared: PreparedDeletion, operationId: String): TransferResult<ConfirmedDeletion> {
        if (!isSourceId(operationId)) return TransferResult.Unavailable("unverified_operation")
        return when (val result = api.operation(operationId)) {
            is TransferResult.Ok -> {
                val operation = result.value
                if (operation.id != operationId || operation.reviewId != prepared.review.reviewId || operation.intent.effect != "archive.delete" || operation.intent.params.sourceIds != prepared.sourceIds || operation.receipt == null) TransferResult.Unavailable("unverified_operation")
                else settle(prepared, ConfirmedDeletion(operation.id, operation.state, operation.receipt))
            }
            // A failed read never establishes that the earlier confirmation failed.
            else -> TransferResult.Unavailable("operation_unavailable")
        }
    }

    private suspend fun settle(prepared: PreparedDeletion, deletion: ConfirmedDeletion): TransferResult<ConfirmedDeletion> {
        if (!isSourceId(deletion.operationId) || deletion.state != "succeeded" || !validReceipt(deletion.receipt, prepared.sourceIds.size)) return TransferResult.Unavailable("unverified_operation")
        return try {
            onDeleted(prepared.sourceIds, prepared.review.preconditions.concerns.toMap())
            journal.removeDeletion(prepared.sourceIds, prepared.journalGeneration)
            TransferResult.Ok(deletion)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The server receipt is valid; replay keeps retrying local cleanup without a new deletion.
            TransferResult.Unavailable("local_cleanup_pending")
        }
    }

    companion object {
        const val RECENT_AUTH_MS = 5 * 60_000L

        fun validReview(review: DeleteReview, requestedIds: List<String>): Boolean = runCatching {
            val ids = requestedIds.sorted()
            val preview = review.preview
            val preconditions = review.preconditions
            val concernIds = preview.concernsErased + preview.concernsDetached
            review.effect == "archive.delete" && review.requiresRecentAuth && isSourceId(review.reviewId) &&
                ids.isNotEmpty() && ids.size <= 50 && ids.distinct().size == ids.size && ids.all(::isSourceId) &&
                review.params.sourceIds == ids && preconditions.sources.keys == ids.toSet() &&
                preconditions.sources.values.all { it > 0 } && preconditions.concerns.values.all { it > 0 } &&
                preconditions.concerns.keys.all(::isSourceId) && concernIds.distinct().size == concernIds.size &&
                concernIds.toSet() == preconditions.concerns.keys && preview.expectedCount == ids.size &&
                preview.sources.map { it.id }.sorted() == ids && preview.attachments >= 0 &&
                preview.sources.all { it.admission in setOf("normal", "quarantine") && Instant.parse(it.receivedAt).toEpochMilli() >= 0 } &&
                Instant.parse(review.expiresAt).toEpochMilli() > 0 && HASH.matches(review.paramsDigest) &&
                review.paramsDigest == deletionDigest(review.params, preconditions)
        }.getOrDefault(false)

        private fun validReceipt(receipt: DeletionReceipt, count: Int) = receipt.ledgerSeq > 0 && HASH.matches(receipt.ledgerRecordHash) && receipt.sourcesDeleted == count
    }
}

/** Same sorted-object canonical JSON as backend/src/http/idempotency.ts; arrays retain order. */
fun deletionDigest(params: DeleteParams, preconditions: DeletePreconditions): String {
    val value = buildJsonObject {
        put("effect", "archive.delete")
        put("params", wireJson.encodeToJsonElement(DeleteParams.serializer(), params))
        put("preconditions", wireJson.encodeToJsonElement(DeletePreconditions.serializer(), preconditions))
    }
    return MessageDigest.getInstance("SHA-256").digest(canonicalReviewJson(value).toByteArray(Charsets.UTF_8)).hex()
}

private fun canonicalReviewJson(value: JsonElement): String = when (value) {
    is JsonObject -> value.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "${JsonPrimitive(it.key)}:${canonicalReviewJson(it.value)}" }
    is JsonArray -> value.joinToString(",", "[", "]", transform = ::canonicalReviewJson)
    else -> value.toString()
}
