package com.digifender.adminifender.core.data.files

import com.digifender.adminifender.core.data.network.SourceSummary
import com.digifender.adminifender.core.data.repository.privacyRank
import kotlinx.coroutines.flow.Flow

/**
 * Where a download stands. [QUEUED] includes a download waiting to retry; [HELD] waits for the owner to sign in again;
 * [FAILED] needs the owner (it is shown with its fixed code and can be requested again).
 */
enum class FileState { QUEUED, DOWNLOADING, READY, FAILED, HELD }

/**
 * One attachment's local copy and the request for it. [generation] changes every time the request is created or
 * restarted, so a worker that started earlier cannot finish, fail or resurrect the new request (or one that was
 * purged and asked for again). [privacyRank] and [quarantined] record how restricted the source was when the owner
 * asked: a source that is later deleted, quarantined or made stricter loses its files (lld.md#synchronization-and-offline-changes).
 */
data class LocalFile(
    val attachmentId: String,
    val sourceId: String,
    val name: String,
    val declaredType: String?,
    val sizeBytes: Long,
    val sha256: String,
    val state: FileState,
    val errorCode: String?,
    val generation: String,
    val blobName: String?,
    val privacyRank: Int,
    val quarantined: Boolean,
    val attempts: Int,
    val requestedAt: Long,
    val updatedAt: Long,
    val lastAccessAt: Long,
    val verifiedAt: Long?,
)

/** Whether the phone's copy may stay, given how the source stands now. A source the phone no longer holds always loses it. */
fun isRestricted(file: LocalFile, source: SourceSummary?): Boolean =
    source == null || privacyRank(source.privacy) > file.privacyRank || (source.quarantined && !file.quarantined)

/** The least recently opened ready copies to remove so that [incoming] bytes fit with the rest under [capBytes]. */
fun evictionPlan(ready: List<LocalFile>, incoming: LocalFile, capBytes: Long): List<LocalFile> {
    val others = ready.filter { it.attachmentId != incoming.attachmentId && it.state == FileState.READY }
    var total = others.sumOf { it.sizeBytes } + incoming.sizeBytes
    val victims = mutableListOf<LocalFile>()
    for (candidate in others.sortedWith(compareBy<LocalFile> { it.lastAccessAt }.thenBy { it.attachmentId })) {
        if (total <= capBytes) break
        total -= candidate.sizeBytes
        victims += candidate
    }
    return victims
}

sealed interface RequestResult {
    data class Requested(val file: LocalFile) : RequestResult

    data class AlreadyReady(val file: LocalFile) : RequestResult

    /** Queued or downloading already: the existing request stands. */
    data class AlreadyActive(val file: LocalFile) : RequestResult

    /** The source is not on this phone any more (deleted, or never synced). */
    data object SourceUnavailable : RequestResult
}

sealed interface CommitResult {
    /** The copy is ready; [evicted] are the blobs of older copies removed to stay under the cap. */
    data class Committed(val file: LocalFile, val evicted: List<String>) : CommitResult

    /** The request was purged, restarted or restricted while the bytes were arriving; the caller discards them. */
    data object Superseded : CommitResult
}

/** What the owner asked for. Everything here is private to the app and cleared at sign-out. */
data class FileRequest(val attachmentId: String, val sourceId: String, val name: String, val declaredType: String?, val sizeBytes: Long, val sha256: String)

/**
 * The durable table of downloads. Every method is one transaction and every change to a running request is fenced by
 * its generation. Methods that remove rows return the blob names the caller must delete from disk (a crash before that
 * leaves an orphan the next [AttachmentRepository.recover] removes).
 */
interface LocalFileStore {
    fun observeAll(): Flow<List<LocalFile>>

    fun observeSource(sourceId: String): Flow<List<LocalFile>>

    suspend fun get(attachmentId: String): LocalFile?

    suspend fun all(): List<LocalFile>

    /** Creates the request, or restarts a failed or held one under a fresh generation. */
    suspend fun request(request: FileRequest, newGeneration: String, nowMs: Long): RequestResult

    /** QUEUED to DOWNLOADING for this generation, counting the attempt. False when another generation owns the row now. */
    suspend fun claim(attachmentId: String, generation: String, nowMs: Long): LocalFile?

    suspend fun fail(attachmentId: String, generation: String, state: FileState, errorCode: String, nowMs: Long): Boolean

    /**
     * Makes the copy ready, in one transaction that re-reads the source: the generation must still be current, the
     * row downloading, and the source still on the phone and no more restricted than when asked. Evicts older copies
     * to fit [capBytes].
     */
    suspend fun commit(attachmentId: String, generation: String, blobName: String, capBytes: Long, nowMs: Long): CommitResult

    suspend fun touch(attachmentId: String, generation: String, nowMs: Long)

    /** Removes the row (and so cancels or deletes the copy) when its generation is still [generation], or whatever the generation is when null. */
    suspend fun remove(attachmentId: String, generation: String?): List<String>

    suspend fun purgeAll(): List<String>

    suspend fun purgeSources(sourceIds: Collection<String>): List<String>

    /** Rows left DOWNLOADING by a process that no longer exists go back to QUEUED. */
    suspend fun requeueInterrupted(beforeMs: Long): List<LocalFile>

    suspend fun blobNames(): Set<String>
}
