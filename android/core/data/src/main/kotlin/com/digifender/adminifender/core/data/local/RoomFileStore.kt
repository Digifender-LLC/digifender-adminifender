package com.digifender.adminifender.core.data.local

import androidx.room.withTransaction
import com.digifender.adminifender.core.data.files.BlobDirectory
import com.digifender.adminifender.core.data.files.CommitResult
import com.digifender.adminifender.core.data.files.FileRequest
import com.digifender.adminifender.core.data.files.FileState
import com.digifender.adminifender.core.data.files.LocalFile
import com.digifender.adminifender.core.data.files.LocalFileStore
import com.digifender.adminifender.core.data.files.RequestResult
import com.digifender.adminifender.core.data.files.evictionPlan
import com.digifender.adminifender.core.data.files.isRestricted
import com.digifender.adminifender.core.data.repository.privacyRank
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal fun LocalFileEntity.toFile() = LocalFile(
    attachmentId, sourceId, name, declaredType, sizeBytes, sha256, FileState.valueOf(state), errorCode, generation, blobName, privacyRank, quarantined, attempts, requestedAt, updatedAt, lastAccessAt, verifiedAt,
)

internal fun LocalFile.toEntity() = LocalFileEntity(
    attachmentId, sourceId, name, declaredType, sizeBytes, sha256, state.name, errorCode, generation, blobName, privacyRank, quarantined, attempts, requestedAt, updatedAt, lastAccessAt, verifiedAt,
)

/**
 * Room-backed [LocalFileStore]. Each method is one transaction and re-reads the source table inside it, so the
 * decision to keep a file is made against the same state sync writes (lld.md#synchronization-and-offline-changes).
 */
class RoomFileStore(private val db: AppDatabase) : LocalFileStore {
    private val files get() = db.localFiles()

    override fun observeAll(): Flow<List<LocalFile>> = files.observeAll().map { rows -> rows.map { it.toFile() } }

    override fun observeSource(sourceId: String): Flow<List<LocalFile>> = files.observeSource(sourceId).map { rows -> rows.map { it.toFile() } }

    override suspend fun get(attachmentId: String) = files.get(attachmentId)?.toFile()

    override suspend fun all() = files.all().map { it.toFile() }

    override suspend fun request(request: FileRequest, newGeneration: String, nowMs: Long): RequestResult = db.withTransaction {
        val source = db.sources().get(request.sourceId) ?: return@withTransaction RequestResult.SourceUnavailable
        val existing = files.get(request.attachmentId)?.toFile()
        if (existing != null && existing.sha256 == request.sha256) {
            when (existing.state) {
                FileState.READY -> {
                    files.put(existing.copy(lastAccessAt = nowMs).toEntity())
                    return@withTransaction RequestResult.AlreadyReady(existing)
                }
                FileState.QUEUED, FileState.DOWNLOADING -> return@withTransaction RequestResult.AlreadyActive(existing)
                FileState.FAILED, FileState.HELD -> Unit
            }
        }
        val row = LocalFile(
            attachmentId = request.attachmentId,
            sourceId = request.sourceId,
            name = request.name,
            declaredType = request.declaredType,
            sizeBytes = request.sizeBytes,
            sha256 = request.sha256,
            state = FileState.QUEUED,
            errorCode = null,
            generation = newGeneration,
            blobName = null,
            privacyRank = privacyRank(source.privacy),
            quarantined = source.quarantined,
            attempts = 0,
            requestedAt = nowMs,
            updatedAt = nowMs,
            lastAccessAt = nowMs,
            verifiedAt = null,
        )
        files.put(row.toEntity())
        RequestResult.Requested(row)
    }

    override suspend fun claim(attachmentId: String, generation: String, nowMs: Long): LocalFile? = db.withTransaction {
        val row = files.get(attachmentId)?.toFile() ?: return@withTransaction null
        if (row.generation != generation || row.state != FileState.QUEUED) return@withTransaction null
        row.copy(state = FileState.DOWNLOADING, attempts = row.attempts + 1, errorCode = null, updatedAt = nowMs).also { files.put(it.toEntity()) }
    }

    override suspend fun fail(attachmentId: String, generation: String, state: FileState, errorCode: String, nowMs: Long): Boolean = db.withTransaction {
        val row = files.get(attachmentId)?.toFile() ?: return@withTransaction false
        if (row.generation != generation || (row.state != FileState.DOWNLOADING && row.state != FileState.QUEUED)) return@withTransaction false
        files.put(row.copy(state = state, errorCode = errorCode, updatedAt = nowMs).toEntity())
        true
    }

    override suspend fun commit(attachmentId: String, generation: String, blobName: String, capBytes: Long, nowMs: Long): CommitResult = db.withTransaction {
        val row = files.get(attachmentId)?.toFile() ?: return@withTransaction CommitResult.Superseded
        if (row.generation != generation || row.state != FileState.DOWNLOADING) return@withTransaction CommitResult.Superseded
        val source = db.sources().get(row.sourceId)?.toSummary()
        if (isRestricted(row, source)) {
            files.delete(attachmentId)
            return@withTransaction CommitResult.Superseded
        }
        val ready = row.copy(state = FileState.READY, errorCode = null, blobName = blobName, updatedAt = nowMs, lastAccessAt = nowMs, verifiedAt = nowMs)
        val victims = evictionPlan(files.all().map { it.toFile() }.filter { it.state == FileState.READY }, ready, capBytes)
        victims.forEach { files.delete(it.attachmentId) }
        files.put(ready.toEntity())
        CommitResult.Committed(ready, victims.mapNotNull { it.blobName })
    }

    override suspend fun touch(attachmentId: String, generation: String, nowMs: Long) = db.withTransaction {
        val row = files.get(attachmentId) ?: return@withTransaction
        if (row.generation == generation) files.put(row.copy(lastAccessAt = nowMs))
    }

    override suspend fun remove(attachmentId: String, generation: String?): List<String> = db.withTransaction {
        val row = files.get(attachmentId) ?: return@withTransaction emptyList()
        if (generation != null && row.generation != generation) return@withTransaction emptyList()
        files.delete(attachmentId)
        listOfNotNull(row.blobName)
    }

    override suspend fun purgeAll(): List<String> = db.withTransaction {
        files.blobNames().also { files.clear() }
    }

    override suspend fun purgeSources(sourceIds: Collection<String>): List<String> = db.withTransaction {
        val names = mutableListOf<String>()
        for (chunk in sourceIds.distinct().chunked(500)) {
            names += files.forSources(chunk).mapNotNull { it.blobName }
            files.deleteForSources(chunk)
        }
        names
    }

    override suspend fun requeueInterrupted(beforeMs: Long): List<LocalFile> = db.withTransaction {
        files.all().filter { it.state == FileState.DOWNLOADING.name && it.updatedAt < beforeMs }.map { it.toFile().copy(state = FileState.QUEUED) }
            .onEach { files.put(it.toEntity()) }
    }

    override suspend fun blobNames(): Set<String> = files.blobNames().toSet()
}

/**
 * Drops the files of sources that are gone, quarantined or stricter than when the owner asked. Runs inside the
 * transaction that changed the sources; the blob names it returns are deleted from disk once that transaction commits.
 */
internal suspend fun AppDatabase.pruneRestrictedFiles(): List<LocalFile> {
    val rows = localFiles().all().map { it.toFile() }
    if (rows.isEmpty()) return emptyList()
    val sources = rows.map { it.sourceId }.distinct().chunked(500).flatMap { sources().getMany(it) }.associate { it.id to it.toSummary() }
    val doomed = rows.filter { isRestricted(it, sources[it.sourceId]) }
    doomed.forEach { localFiles().delete(it.attachmentId) }
    return doomed
}

/**
 * Told, after the transaction that dropped them has committed, which downloads sync just removed (a deleted, quarantined
 * or stricter email): the app stops their work and closes their connections. It is a signal, not the guard: the guard is
 * the row, whose absence makes any late commit fail.
 */
fun interface FilesDropped {
    suspend fun dropped(attachmentIds: List<String>)
}

/** Disk and work side of the prune: a blob a failed delete leaves behind is an orphan the next start removes. */
internal suspend fun BlobDirectory?.afterPrune(dropped: List<LocalFile>, signal: FilesDropped?) {
    if (dropped.isEmpty()) return
    signal?.dropped(dropped.map { it.attachmentId })
    val names = dropped.mapNotNull { it.blobName }
    if (this != null && names.isNotEmpty()) delete(names)
}
