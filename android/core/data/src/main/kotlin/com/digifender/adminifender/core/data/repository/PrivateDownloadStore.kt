package com.digifender.adminifender.core.data.repository

import com.digifender.adminifender.core.data.network.TransferResult
import com.digifender.adminifender.core.data.network.isSourceId
import com.digifender.adminifender.core.data.network.transferFailure
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** This is an opaque private-cache receipt, never a file URI or a permission grant to another app. */
data class SavedDownload(val id: String, val bytes: Long)

/**
 * [root] must be beneath Context.noBackupFilesDir (credential encrypted, excluded from device backup).
 * UUIDs are the only path components; neither mail filenames nor Content-Disposition names reach disk paths.
 * Purging advances a fence before unlinking, so a suspended/in-flight download cannot publish after sign-out,
 * a source tombstone, rebootstrap or review confirmation. Partial files are never visible as saved downloads.
 */
class PrivateDownloadStore(private val root: File, private val maxAttachmentCacheBytes: Long = 500L * 1024 * 1024, private val deleteTree: (File) -> Boolean = { it.deleteRecursively() }) {
    private val lock = Any()
    private var generation = 0L
    private var closed = false
    private val _changes = MutableStateFlow(0L)
    val changes = _changes.asStateFlow()
    private fun changed() { _changes.value++ }
    val fence: Long get() = synchronized(lock) { generation }

    /** Close synchronously before suspending for sign-out/reset; only a fresh signed-in session reopens it. */
    fun close() = synchronized(lock) { closed = true; generation++; changed(); Unit }
    fun open() = synchronized(lock) { closed = false; generation++; changed(); Unit }

    init {
        synchronized(lock) {
            // A process death can leave only temporary bytes; they are not reusable downloads.
            root.walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.forEach { it.delete() }
        }
    }

    suspend fun attachment(sourceId: String, id: String, expectedFence: Long = fence, fetch: suspend (OutputStream) -> TransferResult<Long>): TransferResult<SavedDownload> {
        if (!isSourceId(sourceId) || !isSourceId(id)) return TransferResult.Rejected("invalid_request")
        return save("attachments/$sourceId", id, expectedFence, fetch)
    }

    suspend fun export(id: String, fetch: suspend (OutputStream) -> TransferResult<Long>, verify: (File) -> Boolean, expectedFence: Long = fence): TransferResult<SavedDownload> {
        if (!isSourceId(id)) return TransferResult.Rejected("invalid_request")
        return save("exports", id, expectedFence, fetch, verify)
    }

    private suspend fun save(directory: String, id: String, expectedFence: Long, fetch: suspend (OutputStream) -> TransferResult<Long>, verify: (File) -> Boolean = { true }): TransferResult<SavedDownload> = withContext(Dispatchers.IO) {
        val start: Long
        val temporary: File
        synchronized(lock) {
            if (closed || generation != expectedFence) return@withContext TransferResult.Rejected("download_invalidated")
            start = expectedFence
            val dir = File(root, directory)
            if (!dir.isDirectory && !dir.mkdirs()) return@withContext TransferResult.Unavailable("storage_unavailable")
            temporary = File(dir, "${UUID.randomUUID()}.part")
        }
        try {
            val result = temporary.outputStream().use { stream ->
                val fenced = object : OutputStream() {
                    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                    override fun write(buffer: ByteArray, offset: Int, length: Int) {
                        synchronized(lock) {
                            if (closed || generation != start) throw IOException("download_invalidated")
                            if (directory.startsWith("attachments/")) reserveAttachmentBytes(length.toLong())
                            stream.write(buffer, offset, length)
                        }
                    }
                }
                fetch(fenced)
            }
            ensureActive()
            if (result !is TransferResult.Ok) return@withContext transferFailure(result)
            if (result.value != temporary.length() || !verify(temporary)) return@withContext TransferResult.Rejected("integrity_mismatch")
            synchronized(lock) {
                if (closed || generation != start) return@withContext TransferResult.Rejected("download_invalidated")
                val target = File(root, "$directory/$id")
                if (directory.startsWith("attachments/")) {
                    if (result.value > maxAttachmentCacheBytes) return@withContext TransferResult.Rejected("file_too_large")
                    evictAttachments(result.value, target)
                } else {
                    // One private export at a time; creating another cannot accumulate inaccessible old ZIPs.
                    File(root, "exports").listFiles().orEmpty().filter { it.isFile && it != temporary && it != target && !it.name.endsWith(".part") }.forEach(::removeTree)
                }
                if (!temporary.renameTo(target)) return@withContext TransferResult.Unavailable("storage_unavailable")
                target.setLastModified(System.currentTimeMillis())
                changed()
                TransferResult.Ok(SavedDownload(id, result.value))
            }
        } catch (_: IOException) {
            TransferResult.Unavailable("storage_unavailable")
        } finally {
            temporary.delete()
        }
    }

    suspend fun removeAttachment(sourceId: String, id: String) = withContext(Dispatchers.IO) {
        require(isSourceId(sourceId) && isSourceId(id))
        synchronized(lock) { generation++; removeTree(File(root, "attachments/$sourceId/$id")); changed(); Unit }
    }

    suspend fun removeExport(id: String) = withContext(Dispatchers.IO) {
        require(isSourceId(id))
        synchronized(lock) { generation++; removeTree(File(root, "exports/$id")); changed(); Unit }
    }

    /** Deletion invalidates all saved exports, since their snapshots may contain the deleted source. */
    suspend fun purgeSources(ids: List<String>) = withContext(Dispatchers.IO) {
        require(ids.all(::isSourceId))
        synchronized(lock) {
            generation++
            ids.forEach { removeTree(File(root, "attachments/$it")) }
            removeTree(File(root, "exports"))
            changed()
            Unit
        }
    }

    suspend fun savedAttachments(sourceId: String): Set<String> = withContext(Dispatchers.IO) {
        require(isSourceId(sourceId))
        synchronized(lock) { if (closed) emptySet() else File(root, "attachments/$sourceId").listFiles().orEmpty().filter { it.isFile && isSourceId(it.name) }.map { it.name }.toSet() }
    }

    suspend fun hasExport(id: String): Boolean = withContext(Dispatchers.IO) {
        require(isSourceId(id))
        synchronized(lock) { !closed && File(root, "exports/$id").isFile }
    }

    /** Copy only to a caller-owned, explicitly chosen destination. No path or URI is exposed by this store. */
    suspend fun copyExport(id: String, output: OutputStream, expectedFence: Long, verify: (File) -> Boolean): TransferResult<Long> = withContext(Dispatchers.IO) {
        if (!isSourceId(id)) return@withContext TransferResult.Rejected("invalid_request")
        try {
            val file = synchronized(lock) {
                if (closed || generation != expectedFence) return@withContext TransferResult.Rejected("download_invalidated")
                File(root, "exports/$id").takeIf { it.isFile } ?: return@withContext TransferResult.Rejected("not_downloaded")
            }
            if (!verify(file)) return@withContext TransferResult.Rejected("integrity_mismatch")
            val input = synchronized(lock) {
                if (closed || generation != expectedFence) return@withContext TransferResult.Rejected("download_invalidated")
                file.inputStream()
            }
            var total = 0L
            input.use {
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    ensureActive()
                    synchronized(lock) { if (closed || generation != expectedFence) return@withContext TransferResult.Rejected("download_invalidated") }
                    val count = it.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    total += count
                    synchronized(lock) { if (closed || generation != expectedFence) return@withContext TransferResult.Rejected("download_invalidated") }
                }
            }
            output.flush()
            synchronized(lock) {
                if (closed || generation != expectedFence) TransferResult.Rejected("download_invalidated") else TransferResult.Ok(total)
            }
        } catch (_: IOException) { TransferResult.Unavailable("copy_failed") }
    }

    /** Include in-flight partials in the byte budget; simultaneous downloads cannot bypass the cache cap. */
    private fun reserveAttachmentBytes(incomingBytes: Long) {
        val files = File(root, "attachments").walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        for (file in files.filter { !it.name.endsWith(".part") }.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.path })) {
            if (total + incomingBytes <= maxAttachmentCacheBytes) return
            val bytes = file.length()
            removeTree(file)
            total -= bytes
        }
        if (total + incomingBytes > maxAttachmentCacheBytes) throw IOException("private_cache_full")
    }

    private fun evictAttachments(incomingBytes: Long, target: File) {
        val files = File(root, "attachments").walkTopDown().filter { it.isFile && !it.name.endsWith(".part") && it != target }.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.path }).toList()
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total + incomingBytes <= maxAttachmentCacheBytes) break
            val bytes = file.length()
            removeTree(file)
            total -= bytes
            changed()
        }
    }

    private fun removeTree(file: File) {
        try {
            if (file.exists() && (!deleteTree(file) || file.exists())) throw IOException("private_cache_cleanup_failed")
        } finally { changed() }
    }

    /** Call before cancelling work/signing out, and before replacing a synced snapshot. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(lock) { generation++; removeTree(root); changed(); Unit }
    }
}
