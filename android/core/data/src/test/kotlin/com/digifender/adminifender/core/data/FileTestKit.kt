package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.files.BlobDirectory
import com.digifender.adminifender.core.data.files.CommitResult
import com.digifender.adminifender.core.data.files.Destination
import com.digifender.adminifender.core.data.files.DownloadScheduler
import com.digifender.adminifender.core.data.files.FileKeys
import com.digifender.adminifender.core.data.files.FileRequest
import com.digifender.adminifender.core.data.files.FileState
import com.digifender.adminifender.core.data.files.LocalFile
import com.digifender.adminifender.core.data.files.LocalFileStore
import com.digifender.adminifender.core.data.files.RequestResult
import com.digifender.adminifender.core.data.files.evictionPlan
import com.digifender.adminifender.core.data.files.isRestricted
import com.digifender.adminifender.core.data.network.AttachmentContentApi
import com.digifender.adminifender.core.data.network.ContentStream
import com.digifender.adminifender.core.data.network.OpenResult
import com.digifender.adminifender.core.data.network.SourceSummary
import com.digifender.adminifender.core.data.repository.privacyRank
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

fun sha256Of(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** A fresh directory under `TMPDIR` when it is set (a build host may keep `/tmp` small), else the JVM's temp directory. */
fun tempDirectory(): File {
    val base = System.getenv("TMPDIR")?.let(::File)?.takeIf { it.isDirectory }
    return (if (base != null) Files.createTempDirectory(base.toPath(), "adminifender-test") else Files.createTempDirectory("adminifender-test")).toFile().also { it.deleteOnExit() }
}

/** A software AES key standing in for the Keystore key: the same algorithm, the same cipher code path. */
class SoftKeys(private var key: SecretKey = newKey()) : FileKeys {
    override fun key() = key

    fun rotate() {
        key = newKey()
    }

    companion object {
        fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}

class FakeScheduler : DownloadScheduler {
    val enqueued = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    var cancelledAll = 0

    override fun enqueue(attachmentId: String) {
        enqueued += attachmentId
    }

    override fun cancel(attachmentId: String) {
        cancelled += attachmentId
    }

    override fun cancelAll() {
        cancelledAll += 1
    }
}

fun source(id: String, revision: Int = 1, quarantined: Boolean = false, privacy: String = "hybrid", state: String = "ready") =
    SourceSummary(id, revision, state, quarantined, privacy, 1_000, "Subject $id", listOf("a@example.com"))

/**
 * Same transaction semantics as RoomFileStore, built from the same pure rules ([evictionPlan], [isRestricted]); the
 * source table is a lambda so a test can delete or restrict a source at an exact moment.
 */
class InMemoryFileStore(private val sources: () -> Map<String, SourceSummary>) : LocalFileStore {
    val rows = MutableStateFlow<Map<String, LocalFile>>(emptyMap())
    private val lock = Any()

    override fun observeAll(): Flow<List<LocalFile>> = rows.map { it.values.toList() }

    override fun observeSource(sourceId: String): Flow<List<LocalFile>> = rows.map { m -> m.values.filter { it.sourceId == sourceId } }

    override suspend fun get(attachmentId: String) = rows.value[attachmentId]

    override suspend fun all() = rows.value.values.toList()

    private fun put(row: LocalFile) {
        rows.value = rows.value + (row.attachmentId to row)
    }

    override suspend fun request(request: FileRequest, newGeneration: String, nowMs: Long): RequestResult = synchronized(lock) {
        val source = sources()[request.sourceId] ?: return RequestResult.SourceUnavailable
        val existing = rows.value[request.attachmentId]
        if (existing != null && existing.sha256 == request.sha256) {
            when (existing.state) {
                FileState.READY -> return RequestResult.AlreadyReady(existing.copy(lastAccessAt = nowMs).also { put(it) })
                FileState.QUEUED, FileState.DOWNLOADING -> return RequestResult.AlreadyActive(existing)
                else -> Unit
            }
        }
        val row = LocalFile(
            request.attachmentId, request.sourceId, request.name, request.declaredType, request.sizeBytes, request.sha256, FileState.QUEUED, null, newGeneration, null,
            privacyRank(source.privacy), source.quarantined, 0, nowMs, nowMs, nowMs, null,
        )
        put(row)
        RequestResult.Requested(row)
    }

    override suspend fun claim(attachmentId: String, generation: String, nowMs: Long): LocalFile? = synchronized(lock) {
        val row = rows.value[attachmentId] ?: return null
        if (row.generation != generation || row.state != FileState.QUEUED) return null
        row.copy(state = FileState.DOWNLOADING, attempts = row.attempts + 1, errorCode = null, updatedAt = nowMs).also { put(it) }
    }

    override suspend fun fail(attachmentId: String, generation: String, state: FileState, errorCode: String, nowMs: Long): Boolean = synchronized(lock) {
        val row = rows.value[attachmentId] ?: return false
        if (row.generation != generation || (row.state != FileState.DOWNLOADING && row.state != FileState.QUEUED)) return false
        put(row.copy(state = state, errorCode = errorCode, updatedAt = nowMs))
        true
    }

    override suspend fun commit(attachmentId: String, generation: String, blobName: String, capBytes: Long, nowMs: Long): CommitResult = synchronized(lock) {
        val row = rows.value[attachmentId] ?: return CommitResult.Superseded
        if (row.generation != generation || row.state != FileState.DOWNLOADING) return CommitResult.Superseded
        if (isRestricted(row, sources()[row.sourceId])) {
            rows.value = rows.value - attachmentId
            return CommitResult.Superseded
        }
        val ready = row.copy(state = FileState.READY, errorCode = null, blobName = blobName, updatedAt = nowMs, lastAccessAt = nowMs, verifiedAt = nowMs)
        val victims = evictionPlan(rows.value.values.toList(), ready, capBytes)
        rows.value = rows.value - victims.map { it.attachmentId }.toSet() + (attachmentId to ready)
        CommitResult.Committed(ready, victims.mapNotNull { it.blobName })
    }

    override suspend fun touch(attachmentId: String, generation: String, nowMs: Long): Unit = synchronized(lock) {
        val row = rows.value[attachmentId] ?: return
        if (row.generation == generation) put(row.copy(lastAccessAt = nowMs))
    }

    override suspend fun remove(attachmentId: String, generation: String?): List<String> = synchronized(lock) {
        val row = rows.value[attachmentId] ?: return emptyList()
        if (generation != null && row.generation != generation) return emptyList()
        rows.value = rows.value - attachmentId
        listOfNotNull(row.blobName)
    }

    override suspend fun purgeAll(): List<String> = synchronized(lock) {
        rows.value.values.mapNotNull { it.blobName }.also { rows.value = emptyMap() }
    }

    override suspend fun purgeSources(sourceIds: Collection<String>): List<String> = synchronized(lock) {
        val doomed = rows.value.values.filter { it.sourceId in sourceIds }
        rows.value = rows.value - doomed.map { it.attachmentId }.toSet()
        doomed.mapNotNull { it.blobName }
    }

    override suspend fun requeueInterrupted(beforeMs: Long): List<LocalFile> = synchronized(lock) {
        rows.value.values.filter { it.state == FileState.DOWNLOADING && it.updatedAt < beforeMs }.map { it.copy(state = FileState.QUEUED) }.onEach { put(it) }
    }

    override suspend fun blobNames(): Set<String> = rows.value.values.mapNotNull { it.blobName }.toSet()
}

/** An input stream with a script: bytes, then optionally an error or a wait for a gate. */
class ScriptedStream(
    private val bytes: ByteArray,
    private val failAfter: Int? = null,
    private val gateAfter: Int? = null,
) : InputStream() {
    private val inner = ByteArrayInputStream(bytes)
    private var served = 0
    private val gate = CountDownLatch(1)

    @Volatile
    var aborted = false

    @Volatile
    var atGate = false
    val reachedGate = CountDownLatch(1)

    fun release() = gate.countDown()

    fun abort() {
        aborted = true
        gate.countDown()
    }

    override fun read(): Int = throw UnsupportedOperationException()

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (aborted) throw IOException("canceled")
        if (failAfter != null && served >= failAfter) throw IOException("connection reset")
        if (gateAfter != null && served >= gateAfter && !atGate) {
            atGate = true
            reachedGate.countDown()
            if (!gate.await(10, TimeUnit.SECONDS)) throw IOException("gate timeout")
            if (aborted) throw IOException("canceled")
        }
        val limit = when {
            failAfter != null -> minOf(len, failAfter - served)
            gateAfter != null && served < gateAfter -> minOf(len, gateAfter - served)
            else -> minOf(len, 8192)
        }
        val n = inner.read(b, off, limit)
        if (n > 0) served += n
        return n
    }
}

/** A scripted content API: queued answers, a log of the IDs asked for, and handles on the streams it handed out. */
class ScriptedContentApi : AttachmentContentApi {
    val answers = ArrayDeque<OpenResult>()
    val asked = mutableListOf<String>()
    val streams = mutableListOf<ScriptedStream>()

    override suspend fun openAttachment(attachmentId: String): OpenResult {
        asked += attachmentId
        return answers.removeFirst()
    }

    fun serve(bytes: ByteArray, declared: String? = sha256Of(bytes), length: Long? = bytes.size.toLong(), failAfter: Int? = null, gateAfter: Int? = null): ScriptedStream {
        val stream = ScriptedStream(bytes, failAfter, gateAfter)
        streams += stream
        answers += OpenResult.Open(ContentStream(length ?: -1, declared, stream, stream::abort))
        return stream
    }
}

/** A destination in a temp file that can be told to fail. */
class FileDestination(private val file: File, override val label: String = "export.zip", private val failWritesAfter: Long? = null, private val refuseDiscard: Boolean = false) : Destination {
    var opened = false
    var discarded = false

    override fun open(): OutputStream {
        opened = true
        val inner = file.outputStream()
        return object : OutputStream() {
            private var written = 0L
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (failWritesAfter != null && written + len > failWritesAfter) throw IOException("no space left")
                inner.write(b, off, len)
                written += len
            }

            override fun close() = inner.close()
        }
    }

    override fun sizeBytes(): Long? = if (file.exists()) file.length() else null

    override fun discard(): Boolean {
        discarded = true
        if (refuseDiscard) return false
        return !file.exists() || file.delete()
    }
}

fun blobsIn(dir: BlobDirectory) = dir.names()

