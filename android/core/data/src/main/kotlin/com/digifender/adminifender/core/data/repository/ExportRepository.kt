package com.digifender.adminifender.core.data.repository

import com.digifender.adminifender.core.data.network.ArchiveBytesApi
import com.digifender.adminifender.core.data.network.ArchiveExport
import com.digifender.adminifender.core.data.network.ExportApi
import com.digifender.adminifender.core.data.network.ExportCounts
import com.digifender.adminifender.core.data.network.ExportOmission
import com.digifender.adminifender.core.data.network.HASH
import com.digifender.adminifender.core.data.network.MAX_EXPORT_BYTES
import com.digifender.adminifender.core.data.network.MAX_RESPONSE_BYTES
import com.digifender.adminifender.core.data.network.TransferResult
import com.digifender.adminifender.core.data.network.hex
import com.digifender.adminifender.core.data.network.isSourceId
import com.digifender.adminifender.core.data.network.wireJson
import com.digifender.adminifender.core.data.network.transferFailure
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.serialization.Serializable

/** Captures one POST /exports request; no retry creates another snapshot under a new identity. */
class ExportRequest internal constructor(internal val key: String, internal val generation: Long)

data class RestoredExport(val request: ExportRequest, val export: ArchiveExport?, val unknownCreation: Boolean)

class ExportRepository(private val api: ExportApi, private val bytes: ArchiveBytesApi, private val downloads: PrivateDownloadStore, private val clock: () -> Long, private val journal: TransferJournal = MemoryTransferJournal()) {
    val changes = downloads.changes
    suspend fun saved(id: String) = downloads.hasExport(id)

    fun request() = ExportRequest(UUID.randomUUID().toString(), journal.generation)

    suspend fun restore(): TransferResult<RestoredExport> {
        val generation = journal.generation
        return try {
        val record = journal.export()
        if (record == null) TransferResult.Ok(RestoredExport(ExportRequest(UUID.randomUUID().toString(), generation), null, false))
        else if (!isSourceId(record.creationKey)) TransferResult.Unavailable("journal_unreadable")
        else {
            val export = record.export?.let { validate(TransferResult.Ok(it)) }
            if (export != null && export !is TransferResult.Ok) TransferResult.Unavailable("journal_unreadable")
            else TransferResult.Ok(RestoredExport(ExportRequest(record.creationKey, generation), (export as? TransferResult.Ok)?.value, record.submitted && record.export == null))
        }
    } catch (_: IOException) { TransferResult.Unavailable("journal_unavailable") }
    }

    suspend fun create(request: ExportRequest): TransferResult<ArchiveExport> = try {
        // Before I/O, retain the body ({}) and its immutable creation key across process death.
        journal.putExport(JournalExport(request.key, submitted = true), request.generation)
        val result = validate(api.createExport(request.key))
        if (result is TransferResult.Ok) journal.putExport(JournalExport(request.key, submitted = true, export = result.value), request.generation)
        result
    } catch (_: IOException) { TransferResult.Unavailable("journal_unavailable") }

    suspend fun forgetCurrent(): TransferResult<Unit> = try {
        journal.removeExport(journal.generation)
        TransferResult.Ok(Unit)
    } catch (_: IOException) { TransferResult.Unavailable("journal_unavailable") }

    suspend fun refresh(id: String): TransferResult<ArchiveExport> {
        if (!isSourceId(id)) return TransferResult.Rejected("invalid_request")
        val result = validate(api.export(id), id)
        return try {
            if (result is TransferResult.Ok && !downloadable(result.value)) downloads.removeExport(id)
            if (result is TransferResult.Rejected && result.code in setOf("expired", "deleted", "not_found")) downloads.removeExport(id)
            result
        } catch (_: IOException) { TransferResult.Unavailable("local_cleanup_pending") }
    }

    suspend fun download(id: String): TransferResult<SavedDownload> {
        val fence = downloads.fence
        val current = when (val result = refresh(id)) {
            is TransferResult.Ok -> result.value
            is TransferResult.Rejected -> return result
            is TransferResult.Unavailable -> return result
            TransferResult.SignIn -> return TransferResult.SignIn
        }
        if (!downloadable(current)) return TransferResult.Rejected(if (expired(current)) "expired" else current.state)
        return downloads.export(id, { output ->
            when (val result = bytes.exportContent(id, output)) {
                is TransferResult.Ok -> {
                    // A deletion during download invalidates this snapshot, even if the old stream finished.
                    when (val latest = refresh(id)) {
                        is TransferResult.Ok -> if (downloadable(latest.value) && latest.value.snapshotSeq == current.snapshotSeq) TransferResult.Ok(result.value) else TransferResult.Rejected("download_invalidated")
                        is TransferResult.Rejected -> latest
                        is TransferResult.Unavailable -> latest
                        TransferResult.SignIn -> TransferResult.SignIn
                    }
                }
                else -> transferFailure(result)
            }
        }, { ExportVerifier.verify(it, current) }, expectedFence = fence)
    }

    suspend fun remove(id: String): TransferResult<Unit> = try {
        downloads.removeExport(id)
        TransferResult.Ok(Unit)
    } catch (_: IOException) { TransferResult.Unavailable("local_cleanup_pending") }

    private fun expired(export: ArchiveExport) = Instant.parse(export.expiresAt).toEpochMilli() <= clock()
    private fun downloadable(export: ArchiveExport) = export.state == "ready" && !expired(export)

    private fun validate(result: TransferResult<ArchiveExport>, expectedId: String? = null): TransferResult<ArchiveExport> {
        if (result !is TransferResult.Ok) return result
        val export = result.value
        val valid = runCatching {
            isSourceId(export.id) && (expectedId == null || expectedId == export.id) && export.snapshotSeq >= 0 &&
                export.state in setOf("pending", "running", "ready", "failed", "invalidated", "expired") &&
                Instant.parse(export.expiresAt).toEpochMilli() > 0 &&
                (export.state != "ready" || (export.counts != null && export.omissions != null)) &&
                (export.counts?.let { listOf(it.sources, it.attachments, it.concerns, it.rules, it.services).all { n -> n >= 0 } } ?: true)
        }.getOrDefault(false)
        if (!valid) return TransferResult.Unavailable("malformed_response")
        return TransferResult.Ok(if (expired(export)) export.copy(state = "expired", counts = null, omissions = null) else export)
    }
}

@Serializable
private data class ManifestFile(val path: String, val sha256: String, val bytes: Long)

@Serializable
private data class ExportManifest(val schemaVersion: Int, val exportId: String, val snapshotSeq: Long, val counts: ExportCounts, val omissions: List<ExportOmission>, val files: List<ManifestFile>)

/** Validate the server manifest in place; no ZIP entry is ever extracted or executed. */
object ExportVerifier {
    fun verify(file: File, expected: ArchiveExport): Boolean = runCatching {
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size in 6..100_000 && entries.none { it.isDirectory })
            require(entries.map { it.name }.distinct().size == entries.size)
            val manifestEntry = zip.getEntry("manifest.json") ?: return false
            require(manifestEntry.size in 1..MAX_RESPONSE_BYTES)
            val manifestText = zip.getInputStream(manifestEntry).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size().toLong() + count <= MAX_RESPONSE_BYTES)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            require(manifestText.size <= MAX_RESPONSE_BYTES)
            val manifest = wireJson.decodeFromString(ExportManifest.serializer(), manifestText.toString(Charsets.UTF_8))
            require(manifest.schemaVersion == 1 && manifest.exportId == expected.id && manifest.snapshotSeq == expected.snapshotSeq)
            require(manifest.counts == expected.counts && manifest.omissions == expected.omissions)
            require(manifest.files.map { it.path }.distinct().size == manifest.files.size)
            require(manifest.files.map { it.path }.toSet() + "manifest.json" == entries.map { it.name }.toSet())
            require(manifest.files.map { it.path }.containsAll(listOf("sources.jsonl", "attachments.jsonl", "concerns.jsonl", "rules.jsonl", "services.jsonl")))
            var total = manifestText.size.toLong()
            val buffer = ByteArray(32 * 1024)
            for (item in manifest.files) {
                require(!item.path.startsWith('/') && '\\' !in item.path && item.path.split('/').none { it == "." || it == ".." || it.isEmpty() })
                require(HASH.matches(item.sha256) && item.bytes in 0..MAX_EXPORT_BYTES)
                val entry = zip.getEntry(item.path) ?: return false
                require(entry.size == item.bytes)
                val digest = MessageDigest.getInstance("SHA-256")
                var length = 0L
                zip.getInputStream(entry).use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        length += count
                        total += count
                        require(length <= item.bytes && total <= MAX_EXPORT_BYTES)
                        digest.update(buffer, 0, count)
                    }
                }
                require(length == item.bytes && digest.digest().hex() == item.sha256)
            }
            true
        }
    }.getOrDefault(false)
}
