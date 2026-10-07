package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.*
import com.digifender.adminifender.core.data.repository.ExportRepository
import com.digifender.adminifender.core.data.repository.ExportVerifier
import com.digifender.adminifender.core.data.repository.PrivateDownloadStore
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val EXPORT_ID = "00000000-0000-4000-9000-000000000031"
private const val EXPORT_NOW = 1_800_000_000_000L
private val counts = ExportCounts(0, 0, 0, 0, 0)
private val exportReady = ArchiveExport(EXPORT_ID, "ready", 23, Instant.ofEpochMilli(EXPORT_NOW + 1000).toString(), counts = counts, omissions = emptyList())

private fun zipBytes(corrupt: Boolean = false, extra: Boolean = false, wrongSnapshot: Boolean = false): ByteArray {
    val contents = mapOf("sources.jsonl" to "", "attachments.jsonl" to "", "concerns.jsonl" to "", "rules.jsonl" to "", "services.jsonl" to "")
    val manifest = buildJsonObject {
        put("schemaVersion", 1); put("exportId", EXPORT_ID); put("snapshotSeq", if (wrongSnapshot) 24 else 23)
        put("counts", wireJson.encodeToJsonElement(ExportCounts.serializer(), counts))
        put("omissions", JsonArray(emptyList()))
        put("files", JsonArray(contents.map { (path, content) -> buildJsonObject {
            put("path", path); put("bytes", content.toByteArray().size)
            put("sha256", if (corrupt) "a".repeat(64) else MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) })
        } }))
    }
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        for ((path, content) in contents + ("manifest.json" to manifest.toString()) + if (extra) mapOf("unmanifested.html" to "active") else emptyMap()) {
            zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray()); zip.closeEntry()
        }
    }
    return output.toByteArray()
}

private class Exports : ExportApi, ArchiveBytesApi {
    var current = exportReady
    var created: TransferResult<ArchiveExport> = TransferResult.Ok(exportReady.copy(state = "pending", counts = null, omissions = null))
    val keys = mutableListOf<String>()
    var invalidateDuringDownload = false
    var content = zipBytes()
    override suspend fun createExport(key: String): TransferResult<ArchiveExport> { keys += key; return created }
    override suspend fun export(id: String) = TransferResult.Ok(current)
    override suspend fun attachment(id: String, hash: String, size: Long, output: OutputStream): TransferResult<Long> = error("not used")
    override suspend fun exportContent(id: String, output: OutputStream): TransferResult<Long> {
        output.write(content)
        if (invalidateDuringDownload) current = current.copy(state = "invalidated")
        return TransferResult.Ok(content.size.toLong())
    }
}

class ExportRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val api = Exports()
    private var now = EXPORT_NOW
    private fun repo() = ExportRepository(api, api, PrivateDownloadStore(folder.root), { now })

    @Test
    fun zipManifestValidatesEveryHashSnapshotAndUnlistedEntry() {
        val zip = folder.newFile()
        zip.writeBytes(zipBytes())
        assertTrue(ExportVerifier.verify(zip, exportReady))
        zip.writeBytes(zipBytes(corrupt = true))
        assertFalse(ExportVerifier.verify(zip, exportReady))
        zip.writeBytes(zipBytes(extra = true))
        assertFalse(ExportVerifier.verify(zip, exportReady))
        zip.writeBytes(zipBytes(wrongSnapshot = true))
        assertFalse(ExportVerifier.verify(zip, exportReady))
        zip.writeBytes("not a zip".toByteArray())
        assertFalse(ExportVerifier.verify(zip, exportReady))
    }

    @Test
    fun retryingUnknownCreationKeepsTheOriginalIdempotencyKey() = runTest {
        val repo = repo()
        val request = repo.request()
        api.created = TransferResult.Unavailable("network")
        assertTrue(repo.create(request) is TransferResult.Unavailable)
        api.created = TransferResult.Ok(exportReady)
        assertTrue(repo.create(request) is TransferResult.Ok)
        assertEquals(api.keys[0], api.keys[1])
    }

    @Test
    fun readySnapshotDownloadsOnlyAfterWholeManifestVerification() = runTest {
        val repo = repo()
        assertTrue(repo.download(EXPORT_ID) is TransferResult.Ok)
        val saved = folder.root.resolve("exports/$EXPORT_ID")
        assertTrue(saved.isFile)
        assertTrue(ExportVerifier.verify(saved, exportReady))
        repo.remove(EXPORT_ID)
        assertFalse(saved.exists())
    }

    @Test
    fun deletionDuringDownloadNeverPublishesTheOldSnapshot() = runTest {
        api.invalidateDuringDownload = true
        assertEquals(TransferResult.Rejected("download_invalidated"), repo().download(EXPORT_ID))
        assertFalse(folder.root.walkTopDown().any { it.isFile })
    }

    @Test
    fun expirationPurgesPreviouslySavedZipAndForbidsDownload() = runTest {
        val repo = repo()
        assertTrue(repo.download(EXPORT_ID) is TransferResult.Ok)
        now += 1001
        val status = repo.refresh(EXPORT_ID) as TransferResult.Ok
        assertEquals("expired", status.value.state)
        assertFalse(folder.root.walkTopDown().any { it.isFile })
        assertEquals(TransferResult.Rejected("expired"), repo.download(EXPORT_ID))
    }

    @Test
    fun malformedReadyMetadataDoesNotEnableDownload() = runTest {
        api.current = exportReady.copy(counts = null)
        assertEquals(TransferResult.Unavailable("malformed_response"), repo().download(EXPORT_ID))
        assertFalse(folder.root.walkTopDown().any { it.isFile })
    }
}
