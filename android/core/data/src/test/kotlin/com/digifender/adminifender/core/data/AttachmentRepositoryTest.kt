package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.*
import com.digifender.adminifender.core.data.repository.AttachmentRepository
import com.digifender.adminifender.core.data.repository.PrivateDownloadStore
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val ATTACH_SOURCE = "00000000-0000-4000-9000-000000000041"
private const val ATTACH_ID = "00000000-0000-4000-9000-000000000042"

private class AttachmentServer : ArchiveApi, ArchiveBytesApi {
    var source: ReadResult<SourceDetailDto> = ReadResult.Ok(SourceDetailDto(
        SourceDto(id = ATTACH_SOURCE, kind = "email", state = "ready", admission = "normal", privacy = "hybrid", revision = 3, receivedAt = "2026-10-07T12:00:00Z", subject = "Synthetic"),
        attachments = listOf(AttachmentDto(ATTACH_ID, "../../active.html", "text/html", 1, "a".repeat(64), "attachment", "stored")),
    ))
    var deleteWhileDownloading = false
    var onRead: suspend () -> Unit = {}
    var downloads = 0
    override suspend fun source(id: String): ReadResult<SourceDetailDto> { onRead(); return source }
    override suspend fun chunks(id: String, cursor: String?, limit: Int): ReadResult<ChunksPageDto> = error("not used")
    override suspend fun search(request: SearchRequest, cursor: String?, limit: Int): ReadResult<SearchPageDto> = error("not used")
    override suspend fun attachment(id: String, hash: String, size: Long, output: OutputStream): TransferResult<Long> {
        downloads++
        output.write(1)
        if (deleteWhileDownloading) source = ReadResult.Unavailable("not_found")
        return TransferResult.Ok(1L)
    }
    override suspend fun exportContent(id: String, output: OutputStream): TransferResult<Long> = error("not used")
}

class AttachmentRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val api = AttachmentServer()
    private fun repo() = AttachmentRepository(api, api, PrivateDownloadStore(folder.root))

    @Test
    fun inventoryMembershipIsRequiredBeforeFetchingBytes() = runTest {
        assertEquals(TransferResult.Rejected("not_found"), repo().download(ATTACH_SOURCE, ATTACH_SOURCE))
        assertEquals(0, api.downloads)
    }

    @Test
    fun signOutDuringInitialSourceReadCannotAdoptTheNewStorageFence() = runTest {
        val downloads = PrivateDownloadStore(folder.root)
        val repo = AttachmentRepository(api, api, downloads)
        api.onRead = { downloads.clear() }
        assertEquals(TransferResult.Rejected("download_invalidated"), repo.download(ATTACH_SOURCE, ATTACH_ID))
        assertEquals(0, api.downloads)
        assertFalse(folder.root.walkTopDown().any { it.isFile })
    }

    @Test
    fun deletionDuringDownloadRemovesThePartialCopy() = runTest {
        api.deleteWhileDownloading = true
        assertEquals(TransferResult.Rejected("not_found"), repo().download(ATTACH_SOURCE, ATTACH_ID))
        assertFalse(folder.root.walkTopDown().any { it.isFile })
    }

    @Test
    fun authorizedBytesUseIdsAndNeverTheEmailFilename() = runTest {
        assertTrue(repo().download(ATTACH_SOURCE, ATTACH_ID) is TransferResult.Ok)
        assertTrue(folder.root.resolve("attachments/$ATTACH_SOURCE/$ATTACH_ID").isFile)
        assertFalse(folder.root.resolve("active.html").exists())
    }
}
