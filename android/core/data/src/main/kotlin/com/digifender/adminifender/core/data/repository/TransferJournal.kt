package com.digifender.adminifender.core.data.repository

import com.digifender.adminifender.core.data.network.ArchiveExport
import com.digifender.adminifender.core.data.network.DeleteReview
import com.digifender.adminifender.core.data.network.hex
import com.digifender.adminifender.core.data.network.isSourceId
import com.digifender.adminifender.core.data.network.wireJson
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

@Serializable
data class JournalDeletion(val sourceIds: List<String>, val creationKey: String, val confirmationKey: String, val review: DeleteReview? = null, val submitted: Boolean = false, val invalidated: Boolean = false)

@Serializable
data class JournalExport(val creationKey: String, val submitted: Boolean = false, val export: ArchiveExport? = null)

/** Persist request identity before I/O; restoration never sends a request or confirms an effect by itself. */
interface TransferJournal {
    val generation: Long
    val invalidatedSources: StateFlow<Set<String>>
    fun close()
    fun open()
    suspend fun deletion(sourceIds: List<String>): JournalDeletion?
    suspend fun putDeletion(record: JournalDeletion, generation: Long)
    suspend fun removeDeletion(sourceIds: List<String>, generation: Long)
    suspend fun export(): JournalExport?
    suspend fun putExport(record: JournalExport, generation: Long)
    suspend fun removeExport(generation: Long)
    suspend fun invalidateSources(sourceIds: List<String>)
    suspend fun retainSources(sourceIds: Set<String>)
    suspend fun clear()
}

/** Only tests/default standalone repositories use memory; the app wires [FileTransferJournal]. */
class MemoryTransferJournal : TransferJournal {
    override var generation = 0L
        private set
    private var closed = false
    override fun close() { closed = true; generation++ }
    override fun open() { closed = false; generation++ }
    private val deletions = mutableMapOf<List<String>, JournalDeletion>()
    private val deletedSources = mutableSetOf<String>()
    private val _invalidatedSources = MutableStateFlow<Set<String>>(emptySet())
    override val invalidatedSources = _invalidatedSources.asStateFlow()
    private var exported: JournalExport? = null
    override suspend fun deletion(sourceIds: List<String>): JournalDeletion? { if (closed) throw IOException("journal_closed"); return deletions[sourceIds.sorted()] }
    override suspend fun putDeletion(record: JournalDeletion, generation: Long) { checkGeneration(generation); deletions[record.sourceIds.sorted()] = protectDeletion(record, deletedSources, deletions[record.sourceIds.sorted()]?.invalidated == true) }
    override suspend fun removeDeletion(sourceIds: List<String>, generation: Long) { checkGeneration(generation); deletions.remove(sourceIds.sorted()) }
    override suspend fun export(): JournalExport? { if (closed) throw IOException("journal_closed"); return exported }
    override suspend fun putExport(record: JournalExport, generation: Long) { checkGeneration(generation); exported = record }
    override suspend fun removeExport(generation: Long) { checkGeneration(generation); exported = null }
    override suspend fun invalidateSources(sourceIds: List<String>) {
        deletedSources += sourceIds
        _invalidatedSources.value = deletedSources.toSet()
        for ((ids, record) in deletions.toMap()) if (ids.any { it in sourceIds }) {
            if (record.submitted) deletions[ids] = protectDeletion(record, deletedSources, true) else deletions.remove(ids)
        }
        exported = exported?.let { it.copy(export = it.export?.copy(state = "invalidated", counts = null, omissions = null)) }
    }
    override suspend fun retainSources(sourceIds: Set<String>) {
        val missing = deletions.keys.flatten().distinct().filter { it !in sourceIds }
        if (missing.isNotEmpty()) invalidateSources(missing)
    }
    override suspend fun clear() { generation++; deletions.clear(); deletedSources.clear(); _invalidatedSources.value = emptySet(); exported = null }
    private fun checkGeneration(expected: Long) { if (closed || generation != expected) throw IOException("journal_invalidated") }
}

/**
 * [root] is an app-private directory beneath Context.noBackupFilesDir. No tokens or assertions are stored.
 * One bounded file per deletion intent and one export file, written by atomic replacement before dispatch.
 * All file operations share a lock and a generation fence; a sign-out or deletion prevents late resurrection.
 */
class FileTransferJournal(private val root: File) : TransferJournal {
    private val lock = Any()
    private var epoch = 0L
    private var closed = false
    override fun close() = synchronized(lock) { closed = true; epoch++; Unit }
    override fun open() = synchronized(lock) { closed = false; epoch++; Unit }
    private val deletedSources = mutableSetOf<String>()
    private val _invalidatedSources = MutableStateFlow<Set<String>>(emptySet())
    override val invalidatedSources = _invalidatedSources.asStateFlow()
    override val generation: Long get() = synchronized(lock) { epoch }

    init {
        synchronized(lock) { root.listFiles().orEmpty().filter { it.name.endsWith(".part") }.forEach(::remove) }
    }

    override suspend fun deletion(sourceIds: List<String>): JournalDeletion? = withContext(Dispatchers.IO) {
        synchronized(lock) { if (closed) throw IOException("journal_closed"); read(deletionFile(sourceIds), JournalDeletion.serializer()) }
    }

    override suspend fun putDeletion(record: JournalDeletion, generation: Long) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            checkGeneration(generation)
            val file = deletionFile(record.sourceIds)
            val invalidated = read(file, JournalDeletion.serializer())?.invalidated == true
            write(file, protectDeletion(record, deletedSources, invalidated), JournalDeletion.serializer())
        }
    }

    override suspend fun removeDeletion(sourceIds: List<String>, generation: Long) = withContext(Dispatchers.IO) {
        synchronized(lock) { checkGeneration(generation); remove(deletionFile(sourceIds)) }
    }

    override suspend fun export(): JournalExport? = withContext(Dispatchers.IO) {
        synchronized(lock) { if (closed) throw IOException("journal_closed"); read(File(root, "export.json"), JournalExport.serializer()) }
    }

    override suspend fun putExport(record: JournalExport, generation: Long) = withContext(Dispatchers.IO) {
        synchronized(lock) { checkGeneration(generation); write(File(root, "export.json"), record, JournalExport.serializer()) }
    }

    override suspend fun removeExport(generation: Long) = withContext(Dispatchers.IO) {
        synchronized(lock) { checkGeneration(generation); remove(File(root, "export.json")) }
    }

    override suspend fun invalidateSources(sourceIds: List<String>) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            deletedSources += sourceIds
            _invalidatedSources.value = deletedSources.toSet()
            val files = root.listFiles().orEmpty().filter { it.name.startsWith("delete-") && it.extension == "json" }
            for (file in files) {
                val record = read(file, JournalDeletion.serializer())
                if (record?.sourceIds?.any { it in sourceIds } == true) {
                    if (record.submitted) write(file, protectDeletion(record, deletedSources, true), JournalDeletion.serializer()) else remove(file)
                }
            }
            val exportFile = File(root, "export.json")
            read(exportFile, JournalExport.serializer())?.let { record ->
                write(exportFile, record.copy(export = record.export?.copy(state = "invalidated", counts = null, omissions = null)), JournalExport.serializer())
            }
            Unit
        }
    }

    override suspend fun retainSources(sourceIds: Set<String>) {
        val missing = withContext(Dispatchers.IO) {
            synchronized(lock) {
                root.listFiles().orEmpty().filter { it.name.startsWith("delete-") && it.extension == "json" }
                    .flatMap { read(it, JournalDeletion.serializer())?.sourceIds.orEmpty() }.distinct().filter { it !in sourceIds }
            }
        }
        if (missing.isNotEmpty()) invalidateSources(missing)
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            epoch++
            deletedSources.clear()
            _invalidatedSources.value = emptySet()
            if (root.exists() && (!root.deleteRecursively() || root.exists())) throw IOException("journal_cleanup_failed")
        }
    }

    private fun deletionFile(ids: List<String>): File {
        require(ids.size in 1..50 && ids.distinct().size == ids.size && ids.all(::isSourceId))
        val hash = MessageDigest.getInstance("SHA-256").digest(ids.sorted().joinToString(",").toByteArray()).hex()
        return File(root, "delete-$hash.json")
    }

    private fun checkGeneration(expected: Long) { if (closed || epoch != expected) throw IOException("journal_invalidated") }

    private fun <T> read(file: File, serializer: KSerializer<T>): T? {
        if (!file.exists()) return null
        if (file.length() > MAX_JOURNAL_BYTES) throw IOException("journal_too_large")
        return try { wireJson.decodeFromString(serializer, file.readText(Charsets.UTF_8)) } catch (_: IllegalArgumentException) { throw IOException("journal_unreadable") }
    }

    private fun <T> write(file: File, record: T, serializer: KSerializer<T>) {
        val bytes = wireJson.encodeToString(serializer, record).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_JOURNAL_BYTES) throw IOException("journal_too_large")
        if (!root.isDirectory && !root.mkdirs()) throw IOException("journal_unavailable")
        if (!file.exists() && root.listFiles().orEmpty().count { it.extension == "json" } >= MAX_JOURNAL_FILES) throw IOException("journal_full")
        val temporary = File(root, "${file.name}.part")
        try {
            temporary.outputStream().use { it.write(bytes); it.fd.sync() }
            if (!temporary.renameTo(file)) throw IOException("journal_unavailable")
        } finally { temporary.delete() }
    }

    private fun remove(file: File) { if (file.exists() && (!file.delete() || file.exists())) throw IOException("journal_cleanup_failed") }

    companion object {
        private const val MAX_JOURNAL_BYTES = 1024 * 1024
        private const val MAX_JOURNAL_FILES = 100
    }
}

/** Keep the exact request identity for unknown confirmations, without retaining the deleted email subjects. */
private fun protectDeletion(record: JournalDeletion, deletedSources: Set<String>, alreadyInvalidated: Boolean): JournalDeletion {
    if (!record.invalidated && !alreadyInvalidated && record.sourceIds.none { it in deletedSources }) return record
    if (!record.submitted) throw IOException("review_invalidated")
    val review = record.review ?: throw IOException("journal_unreadable")
    return record.copy(invalidated = true, review = review.copy(preview = review.preview.copy(sources = review.preview.sources.map { it.copy(subject = null) }, derived = emptyList())))
}
