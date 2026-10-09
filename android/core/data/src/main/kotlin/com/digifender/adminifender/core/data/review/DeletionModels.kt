package com.digifender.adminifender.core.data.review

import com.digifender.adminifender.core.data.network.Cleanup
import com.digifender.adminifender.core.data.network.OperationReceipt
import com.digifender.adminifender.core.data.network.OperationState
import com.digifender.adminifender.core.data.network.ReviewState
import kotlinx.coroutines.flow.Flow

/**
 * Where an archive deletion stands on this phone. The row is written before every request, so a crash leaves an honest
 * trace and never a silent retry.
 *
 * - [PREPARING]: the selection and the key for `POST /reviews` are stored; the answer may not have arrived. Sending again with the same key is safe and replays the same review.
 * - [PREPARED]: the Worker's exact review is stored. Nothing has been deleted.
 * - [CONFIRMING]: the confirmation key is stored and the owner is being asked for the passkey. Nothing has been sent.
 * - [SENDING]: the confirmation was handed to the network. If no answer was recorded, the outcome is **unknown**: only a read-back may settle it.
 * - [SUCCEEDED]: the Worker's receipt is stored and the private copies were removed from this phone.
 * - [REJECTED]: the Worker answered that nothing was deleted by this review.
 * - [STOPPED]: the owner stopped tracking an outcome that stayed unknown.
 */
enum class DeletionPhase { PREPARING, PREPARED, CONFIRMING, SENDING, SUCCEEDED, REJECTED, STOPPED }

/** The content-free part of the review the phone keeps. Subjects are never stored: the screen takes them from the synced cache. */
data class StoredPreview(val attachments: Int, val concernsErased: List<String>, val concernsDetached: List<String>, val derived: List<String>, val expectedCount: Int)

/** The last read-back of the review: what the Worker said it was when asked, and when. It says nothing about the future. */
data class StoredReadback(val state: ReviewState, val operationState: OperationState?, val checkedAtMs: Long)

data class DeletionReview(
    val localId: String,
    val sourceIds: List<String>,
    val prepareKey: String,
    val phase: DeletionPhase,
    val errorCode: String?,
    val reviewId: String?,
    val paramsDigest: String?,
    val expiresAtMs: Long?,
    val sourceRevisions: Map<String, Int>,
    val concernRevisions: Map<String, Int>,
    val preview: StoredPreview?,
    val confirmKey: String?,
    val operationId: String?,
    val receipt: OperationReceipt.Deletion?,
    val cleanup: Cleanup?,
    val cleanupCheckedAtMs: Long?,
    val readback: StoredReadback?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    /** True when the confirmation may have reached the Worker and no answer was recorded. */
    val outcomeUnknown: Boolean get() = phase == DeletionPhase.SENDING
}

/** The durable table of deletion reviews; the repository is its only writer. */
interface DeletionStore {
    fun observeAll(): Flow<List<DeletionReview>>

    fun observe(localId: String): Flow<DeletionReview?>

    suspend fun get(localId: String): DeletionReview?

    suspend fun all(): List<DeletionReview>

    suspend fun put(review: DeletionReview)

    suspend fun delete(localId: String)
}

/**
 * Removes what the phone holds of sources the owner just deleted, in one step: the source rows (with a tombstone so a
 * stale page cannot bring them back), their cached text, their downloaded files and the chat answers drawn from them.
 */
fun interface DeletedSourcePurge {
    suspend fun purge(sourceIds: Collection<String>, nowMs: Long)
}
