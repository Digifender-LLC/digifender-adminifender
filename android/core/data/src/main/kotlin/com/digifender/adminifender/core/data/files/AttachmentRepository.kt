package com.digifender.adminifender.core.data.files

import com.digifender.adminifender.core.data.network.AttachmentContentApi
import com.digifender.adminifender.core.data.network.ContentStream
import com.digifender.adminifender.core.data.network.OpenResult
import com.digifender.adminifender.core.data.network.isSha256Hex
import com.digifender.adminifender.core.data.repository.AttachmentInfo
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Starts and stops the background download of one attachment. Unique per attachment; completion never claims more than the file row says. */
interface DownloadScheduler {
    fun enqueue(attachmentId: String)

    fun cancel(attachmentId: String)

    fun cancelAll()
}

/** An owner-chosen place for bytes to go (the Storage Access Framework in the app, a file in tests). The app never writes outside private storage on its own. */
interface Destination {
    /** A name to show the owner, never a path. */
    val label: String

    fun open(): OutputStream

    /** The size the destination reports after the stream was closed, or null when it cannot say. */
    fun sizeBytes(): Long?

    /** Removes what was written. True when nothing of it remains (or nothing was ever created). */
    fun discard(): Boolean
}

sealed interface AskOutcome {
    data object Asked : AskOutcome

    data object AlreadyReady : AskOutcome

    data object AlreadyActive : AskOutcome

    /** Larger than the largest file the server admits; the inventory stays, the download is not offered. */
    data class TooLarge(val limitBytes: Long) : AskOutcome

    /** The inventory predates the checksum the phone needs to verify a file; opening the email again refreshes it. */
    data object NeedsRefresh : AskOutcome

    data object SourceUnavailable : AskOutcome
}

sealed interface DownloadResult {
    data object Done : DownloadResult

    /** Nothing to do: purged, restarted, finished or restricted since this run was queued. */
    data object Superseded : DownloadResult

    /** Try again later: the row is queued again and WorkManager retries with backoff. */
    data class Retry(val code: String) : DownloadResult

    data object SignIn : DownloadResult

    data class Failed(val code: String) : DownloadResult
}

sealed interface FileRead {
    data class Bytes(val file: LocalFile, val bytes: ByteArray) : FileRead

    /** No ready copy on this phone (never downloaded, evicted, purged or restricted since). */
    data object NotAvailable : FileRead

    /** The copy failed its checks and was removed; download it again. */
    data object Damaged : FileRead
}

sealed interface SaveOutcome {
    data class Saved(val bytes: Long) : SaveOutcome

    data object NotAvailable : SaveOutcome

    data object Damaged : SaveOutcome

    /** Writing to the owner's destination failed. The partial file is discarded if the destination allows; [discarded] says whether it did. */
    data class WriteFailed(val discarded: Boolean) : SaveOutcome
}

private const val CHUNK = 64 * 1024

/** Space kept free beyond a download so the phone is not filled to the last byte. */
private const val RESERVE_BYTES = 32L * 1024 * 1024

/** Automatic attempts for a download that keeps failing in transit; the owner can ask again after that. */
const val MAX_DOWNLOAD_ATTEMPTS = 5

/**
 * Attachment bytes on this phone: requested by the owner, downloaded in the background into AES-GCM sealed files,
 * verified against the size and SHA-256 the inventory promised, and kept in a 500 MiB least-recently-used cache
 * (baseline.md). The Room table is the authority: a file counts only while a ready row of the current generation names it.
 */
class AttachmentRepository(
    private val api: AttachmentContentApi,
    private val store: LocalFileStore,
    private val blobs: BlobDirectory,
    private val cipher: FileCipher,
    private val scheduler: DownloadScheduler,
    private val clock: () -> Long,
    private val capBytes: Long = ATTACHMENT_CACHE_BYTES,
    private val maxBytes: Long = MAX_ATTACHMENT_BYTES,
    private val newGeneration: () -> String = { UUID.randomUUID().toString() },
) {
    /** Rows left DOWNLOADING before this process started were interrupted by it ending. */
    private val startedAt = clock()
    private val running = mutableMapOf<String, Job>()
    private val lock = Mutex()

    fun files(sourceId: String): Flow<Map<String, LocalFile>> = store.observeSource(sourceId).map { rows -> rows.associateBy { it.attachmentId } }

    fun allFiles(): Flow<List<LocalFile>> = store.observeAll()

    /** The owner asked for the bytes. Stores the request first, then starts the work; asking twice is one request. */
    suspend fun ask(sourceId: String, info: AttachmentInfo): AskOutcome {
        if (info.sizeBytes < 0 || info.sizeBytes > maxBytes) return AskOutcome.TooLarge(maxBytes)
        if (!isSha256Hex(info.sha256)) return AskOutcome.NeedsRefresh
        val request = FileRequest(info.id, sourceId, info.name, info.declaredType, info.sizeBytes, info.sha256)
        return when (val result = store.request(request, newGeneration(), clock())) {
            is RequestResult.Requested -> AskOutcome.Asked.also { scheduler.enqueue(info.id) }
            is RequestResult.AlreadyReady -> AskOutcome.AlreadyReady
            is RequestResult.AlreadyActive -> AskOutcome.AlreadyActive.also { scheduler.enqueue(info.id) }
            RequestResult.SourceUnavailable -> AskOutcome.SourceUnavailable
        }
    }

    /** Stops a download or deletes a copy. The row goes first, so a worker that is still running can no longer commit. */
    suspend fun remove(attachmentId: String) {
        scheduler.cancel(attachmentId)
        cancelRunning(attachmentId)
        blobs.delete(store.remove(attachmentId, null))
    }

    private suspend fun cancelRunning(attachmentId: String) {
        val job = lock.withLock { running.remove(attachmentId) }
        job?.cancel()
    }

    /**
     * Stops the downloads of these attachments right now: their queued work is cancelled and a transfer in progress has
     * its connection closed. Used when sync removes or restricts the files' emails, after the rows are gone; the
     * generation fence is what stops a late commit, this is what stops the bytes arriving at all.
     */
    suspend fun stopDownloads(attachmentIds: Collection<String>) {
        for (id in attachmentIds.distinct()) {
            scheduler.cancel(id)
            cancelRunning(id)
        }
    }

    /** Removes every downloaded file and request: sign-out, a refused session and a revoked device (lld.md#synchronization-and-offline-changes). */
    suspend fun purgeAll() {
        scheduler.cancelAll()
        val jobs = lock.withLock { running.values.toList().also { running.clear() } }
        jobs.forEach { it.cancel() }
        val names = store.purgeAll()
        blobs.delete(names)
        // Anything the rows did not name goes too: this is the private-data purge, not the periodic sweep.
        blobs.deleteAll()
    }

    /** Files of sources the owner just deleted: gone at once, without waiting for sync to say so. */
    suspend fun purgeSources(sourceIds: Collection<String>) {
        if (sourceIds.isEmpty()) return
        for (file in store.all().filter { it.sourceId in sourceIds }) {
            scheduler.cancel(file.attachmentId)
            cancelRunning(file.attachmentId)
        }
        blobs.delete(store.purgeSources(sourceIds))
    }

    /** Start of the app: requeues interrupted downloads, restarts their work, and removes files no row names. */
    suspend fun recover() {
        for (file in store.requeueInterrupted(startedAt)) scheduler.enqueue(file.attachmentId)
        for (file in store.all().filter { it.state == FileState.QUEUED }) scheduler.enqueue(file.attachmentId)
        blobs.sweep(store.blobNames(), startedAt)
    }

    /** After the owner signs in again: held downloads continue. */
    suspend fun resumeHeld() {
        for (file in store.all().filter { it.state == FileState.HELD }) {
            // A held request restarts under a new generation, exactly as if asked again.
            val request = FileRequest(file.attachmentId, file.sourceId, file.name, file.declaredType, file.sizeBytes, file.sha256)
            if (store.request(request, newGeneration(), clock()) is RequestResult.Requested) scheduler.enqueue(file.attachmentId)
        }
    }

    // -- the download -----------------------------------------------------------------------------------------------------

    /** One run of the download for [attachmentId]. Safe to call again; a purged or restarted request makes it a no-op. */
    suspend fun run(attachmentId: String): DownloadResult {
        val row = store.get(attachmentId) ?: return DownloadResult.Superseded
        if (row.state != FileState.QUEUED) return if (row.state == FileState.READY) DownloadResult.Done else DownloadResult.Superseded
        val generation = row.generation
        val claimed = store.claim(attachmentId, generation, clock()) ?: return DownloadResult.Superseded
        return try {
            coroutineScope {
                val work = async { download(claimed) }
                lock.withLock { running[attachmentId] = work }
                try {
                    work.await()
                } finally {
                    lock.withLock { if (running[attachmentId] === work) running.remove(attachmentId) }
                }
            }
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                // WorkManager stopped this run (constraints lost, process ending). The request goes back to queued so the next run
                // can claim it; if the owner removed or restarted it meanwhile, the generation fence makes this a no-op.
                withContext(NonCancellable) { store.fail(attachmentId, generation, FileState.QUEUED, "interrupted", clock()) }
                throw e
            }
            // The owner removing this download only ends this run.
            DownloadResult.Superseded
        }
    }

    private suspend fun download(file: LocalFile): DownloadResult {
        val id = file.attachmentId
        val generation = file.generation
        suspend fun fail(state: FileState, code: String): DownloadResult {
            val current = store.fail(id, generation, state, code, clock())
            return when {
                !current -> DownloadResult.Superseded
                state == FileState.HELD -> DownloadResult.SignIn
                state == FileState.QUEUED -> DownloadResult.Retry(code)
                else -> DownloadResult.Failed(code)
            }
        }
        if (file.sizeBytes < 0 || file.sizeBytes > maxBytes) return fail(FileState.FAILED, "too_large")
        if (blobs.usableBytes() < cipher.sealedSize(file.sizeBytes) + RESERVE_BYTES) return fail(FileState.FAILED, "no_space")
        return when (val opened = api.openAttachment(id)) {
            OpenResult.SessionExpired -> fail(FileState.HELD, "sign_in_required")
            is OpenResult.Gone -> fail(FileState.FAILED, "gone")
            is OpenResult.Refused -> fail(FileState.FAILED, opened.code)
            is OpenResult.Unavailable -> retryOrFail(file, opened.code, ::fail)
            is OpenResult.Open -> opened.stream.use { stream -> transfer(file, stream, ::fail) }
        }
    }

    private suspend fun retryOrFail(file: LocalFile, code: String, fail: suspend (FileState, String) -> DownloadResult): DownloadResult =
        // `claim` counted this attempt already.
        if (file.attempts >= MAX_DOWNLOAD_ATTEMPTS) fail(FileState.FAILED, code) else fail(FileState.QUEUED, code)

    private suspend fun transfer(file: LocalFile, stream: ContentStream, fail: suspend (FileState, String) -> DownloadResult): DownloadResult {
        // The inventory is the promise: size and checksum are checked against it before and after, never trusted from the transfer.
        if (stream.contentLength >= 0 && stream.contentLength != file.sizeBytes) return fail(FileState.FAILED, "size_mismatch")
        stream.declaredSha256?.let { if (it != file.sha256) return fail(FileState.FAILED, "hash_mismatch") }
        val temp = blobs.newTemp()
        var blob: String? = null
        var committed = false
        try {
            when (val outcome = copySealed(file, stream, temp)) {
                // A disconnect part-way counts as an attempt exactly like one before the first byte: the cap holds for both.
                is Copy.Failed -> return if (outcome.state == FileState.QUEUED) retryOrFail(file, outcome.code, fail) else fail(outcome.state, outcome.code)
                Copy.Done -> Unit
            }
            val finished = blobs.finish(temp)
            blob = finished
            // The commit is not cancelled half-way: its result decides whether the finished file is kept or removed.
            val result = withContext(NonCancellable) { store.commit(file.attachmentId, file.generation, finished, capBytes, clock()) }
            return when (result) {
                is CommitResult.Committed -> {
                    committed = true
                    blobs.delete(result.evicted)
                    DownloadResult.Done
                }
                CommitResult.Superseded -> DownloadResult.Superseded
            }
        } finally {
            // Whatever did not become a ready row's file goes at once: a temp file, or a finished blob that was never committed.
            if (!committed) {
                temp.delete()
                blob?.let { blobs.delete(it) }
            }
        }
    }

    private sealed interface Copy {
        data object Done : Copy

        data class Failed(val state: FileState, val code: String) : Copy
    }

    /** Streams into the sealed temp file, counting and hashing as it goes; stops at the first byte past the promised size. */
    private suspend fun copySealed(file: LocalFile, stream: ContentStream, temp: File): Copy = blockingWithAbort(stream::abort) {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        val buffer = ByteArray(CHUNK)
        try {
            FileOutputStream(temp).use { raw ->
                val sealed = cipher.seal(BufferedOutputStream(raw, CHUNK), file.attachmentId, file.sha256)
                while (true) {
                    ensureActive()
                    val n = try {
                        stream.body.read(buffer)
                    } catch (_: IOException) {
                        return@blockingWithAbort Copy.Failed(FileState.QUEUED, "network")
                    }
                    if (n < 0) break
                    count += n
                    if (count > file.sizeBytes) return@blockingWithAbort Copy.Failed(FileState.FAILED, "size_mismatch")
                    digest.update(buffer, 0, n)
                    sealed.write(buffer, 0, n)
                }
                sealed.close()
            }
        } catch (_: IOException) {
            return@blockingWithAbort Copy.Failed(FileState.FAILED, "storage")
        } catch (_: java.security.GeneralSecurityException) {
            return@blockingWithAbort Copy.Failed(FileState.FAILED, "storage_key")
        }
        when {
            count != file.sizeBytes -> Copy.Failed(FileState.FAILED, "size_mismatch")
            digest.digest().toHex() != file.sha256 -> Copy.Failed(FileState.FAILED, "hash_mismatch")
            else -> Copy.Done
        }
    }

    // -- reading what is on the phone -------------------------------------------------------------------------------------

    /** The authenticated bytes of a ready copy, handed out only if the row is still the one they were read for. */
    suspend fun read(attachmentId: String): FileRead {
        val row = store.get(attachmentId)?.takeIf { it.state == FileState.READY } ?: return FileRead.NotAvailable
        val blob = row.blobName?.let(blobs::file)
        if (blob == null || !blob.isFile) {
            store.remove(attachmentId, row.generation)
            return FileRead.NotAvailable
        }
        val plain = try {
            withContext(Dispatchers.IO) { FileInputStream(blob).use { cipher.open(it, row.attachmentId, row.sha256, row.sizeBytes) } }
        } catch (_: IOException) {
            return damaged(row)
        }
        if (plain.size.toLong() != row.sizeBytes || MessageDigest.getInstance("SHA-256").digest(plain).toHex() != row.sha256) return damaged(row)
        // A purge, restart or restriction while the file was read means these bytes are no longer the phone's to hand out.
        val still = store.get(attachmentId)
        if (still == null || still.generation != row.generation || still.state != FileState.READY) return FileRead.NotAvailable
        store.touch(attachmentId, row.generation, clock())
        return FileRead.Bytes(row, plain)
    }

    private suspend fun damaged(row: LocalFile): FileRead {
        blobs.delete(store.remove(row.attachmentId, row.generation))
        return FileRead.Damaged
    }

    /** Writes a verified copy to a destination the owner chose. This is a plaintext copy outside the app's private storage, which the app cannot recall. */
    suspend fun saveCopy(attachmentId: String, destination: Destination): SaveOutcome {
        val bytes = when (val read = read(attachmentId)) {
            is FileRead.Bytes -> read.bytes
            FileRead.NotAvailable -> return SaveOutcome.NotAvailable
            FileRead.Damaged -> return SaveOutcome.Damaged
        }
        return try {
            withContext(Dispatchers.IO) {
                destination.open().use { it.write(bytes) }
            }
            if (destination.sizeBytes()?.let { it != bytes.size.toLong() } == true) SaveOutcome.WriteFailed(destination.discard()) else SaveOutcome.Saved(bytes.size.toLong())
        } catch (e: CancellationException) {
            destination.discard()
            throw e
        } catch (_: IOException) {
            SaveOutcome.WriteFailed(destination.discard())
        }
    }
}

internal fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xff
        out[i * 2] = digits[v ushr 4]
        out[i * 2 + 1] = digits[v and 0x0f]
    }
    return String(out)
}
