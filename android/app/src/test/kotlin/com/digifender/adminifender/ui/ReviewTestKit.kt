package com.digifender.adminifender.ui

import com.digifender.adminifender.core.data.network.ArchiveView
import com.digifender.adminifender.core.data.network.ConfirmResult
import com.digifender.adminifender.core.data.network.ConfirmedReview
import com.digifender.adminifender.core.data.network.DeletionPreview
import com.digifender.adminifender.core.data.network.DeletionPreviewSource
import com.digifender.adminifender.core.data.network.Operation
import com.digifender.adminifender.core.data.network.OperationReceipt
import com.digifender.adminifender.core.data.network.OperationState
import com.digifender.adminifender.core.data.network.PrepareResult
import com.digifender.adminifender.core.data.network.PreparedDeletion
import com.digifender.adminifender.core.data.network.PreparedReview
import com.digifender.adminifender.core.data.network.ReviewApi
import com.digifender.adminifender.core.data.network.ReviewEffect
import com.digifender.adminifender.core.data.network.ReviewRead
import com.digifender.adminifender.core.data.network.ReviewReadback
import com.digifender.adminifender.core.data.network.ReviewState
import com.digifender.adminifender.core.data.network.SourceSummary
import com.digifender.adminifender.core.data.repository.ArchiveCache
import com.digifender.adminifender.core.data.repository.CachedSearchHit
import com.digifender.adminifender.core.data.repository.SourceText
import com.digifender.adminifender.core.data.review.DeletedSourcePurge
import com.digifender.adminifender.core.data.review.DeletionReview
import com.digifender.adminifender.core.data.review.DeletionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

val DIGEST = "a".repeat(64)

fun uuid(n: Int) = "%08x-0000-4000-8000-%012x".format(n, n)

class InMemoryDeletionStore : DeletionStore {
    val rows = MutableStateFlow<Map<String, DeletionReview>>(emptyMap())
    val writes = mutableListOf<DeletionReview>()

    override fun observeAll(): Flow<List<DeletionReview>> = rows.map { it.values.sortedBy { r -> r.createdAtMs } }

    override fun observe(localId: String): Flow<DeletionReview?> = rows.map { it[localId] }

    override suspend fun get(localId: String) = rows.value[localId]

    override suspend fun all() = rows.value.values.toList()

    override suspend fun put(review: DeletionReview) {
        writes += review
        rows.value = rows.value + (review.localId to review)
    }

    override suspend fun delete(localId: String) {
        rows.value = rows.value - localId
    }
}

class RecordingPurge : DeletedSourcePurge {
    val purged = mutableListOf<List<String>>()

    override suspend fun purge(sourceIds: Collection<String>, nowMs: Long) {
        purged += sourceIds.toList()
    }
}

/** Only `sources(ids)` matters to the deletion flow; the rest of the cache is not used by it. */
class FakeArchive : ArchiveCache {
    val held = MutableStateFlow<Map<String, SourceSummary>>(emptyMap())

    override fun sources(view: ArchiveView, limit: Int): Flow<List<SourceSummary>> = held.map { it.values.toList() }
    override fun count(view: ArchiveView): Flow<Int> = held.map { it.size }
    override fun source(id: String): Flow<SourceSummary?> = held.map { it[id] }
    override fun sources(ids: List<String>): Flow<List<SourceSummary>> = held.map { m -> ids.mapNotNull { m[it] } }
    override fun text(id: String): Flow<SourceText?> = flowOf(null)
    override suspend fun search(terms: List<String>, view: ArchiveView, sender: String?, fromMs: Long?, toMs: Long?, limit: Int): List<CachedSearchHit> = emptyList()
    override suspend fun putText(id: String, text: SourceText, nowMs: Long) = false
}

/** Every call is answered by a lambda a test can suspend, so a response can arrive after anything else has happened. */
class FakeReviewApi : ReviewApi {
    var onPrepare: suspend (String, List<String>) -> PrepareResult = { _, _ -> PrepareResult.NoAnswer("network") }
    var onConfirm: suspend (String, String, String) -> ConfirmResult = { _, _, _ -> ConfirmResult.NoAnswer("network") }
    var onReadReview: suspend (String) -> ReviewRead<ReviewReadback> = { ReviewRead.Unavailable("network") }
    var onReadOperation: suspend (String) -> ReviewRead<Operation> = { ReviewRead.Unavailable("network") }
    val prepareCalls = mutableListOf<Pair<String, List<String>>>()
    val confirmCalls = mutableListOf<Triple<String, String, String>>()
    val readReviewCalls = mutableListOf<String>()

    override suspend fun prepareDeletion(idempotencyKey: String, sourceIds: List<String>): PrepareResult {
        prepareCalls += idempotencyKey to sourceIds
        return onPrepare(idempotencyKey, sourceIds)
    }

    override suspend fun confirm(reviewId: String, idempotencyKey: String, paramsDigest: String): ConfirmResult {
        confirmCalls += Triple(reviewId, idempotencyKey, paramsDigest)
        return onConfirm(reviewId, idempotencyKey, paramsDigest)
    }

    override suspend fun readReview(reviewId: String): ReviewRead<ReviewReadback> {
        readReviewCalls += reviewId
        return onReadReview(reviewId)
    }

    override suspend fun readOperation(operationId: String): ReviewRead<Operation> = onReadOperation(operationId)
}

fun preparedFor(ids: List<String>, reviewId: String = uuid(900), expiresAtMs: Long = 600_000, digest: String = DIGEST, revision: Int = 1): PrepareResult.Prepared =
    PrepareResult.Prepared(
        PreparedReview.Deletion(
            PreparedDeletion(
                reviewId,
                ids.sorted(),
                digest,
                ids.associateWith { revision },
                emptyMap(),
                DeletionPreview(ids.map { DeletionPreviewSource(it, "Subject $it", 1_000, false) }, 2, emptyList(), emptyList(), listOf("search index"), ids.size),
                expiresAtMs,
            ),
        ),
    )

fun readback(state: ReviewState, reviewId: String = uuid(900), operationId: String? = null, operationState: OperationState? = null, expiresAtMs: Long = 600_000, digest: String = DIGEST) =
    ReviewRead.Ok(ReviewReadback(reviewId, ReviewEffect.ARCHIVE_DELETE, digest, state, 0, expiresAtMs, operationId, operationState))

fun receipt(count: Int) = OperationReceipt.Deletion(7, "b".repeat(64), count)

fun succeededOperation(ids: List<String>, operationId: String, reviewId: String = uuid(900)) = ReviewRead.Ok(
    Operation(operationId, reviewId, OperationState.SUCCEEDED, ReviewEffect.ARCHIVE_DELETE, ids.sorted(), null, receipt(ids.size), null, 0, 0, null),
)

fun confirmed(ids: List<String>, operationId: String) = ConfirmResult.Confirmed(ConfirmedReview(operationId, receipt(ids.size)))
