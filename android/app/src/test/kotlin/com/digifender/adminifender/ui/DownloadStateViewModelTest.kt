package com.digifender.adminifender.ui

import androidx.lifecycle.ViewModelStore
import com.digifender.adminifender.core.data.network.*
import com.digifender.adminifender.core.data.repository.AttachmentRepository
import com.digifender.adminifender.core.data.repository.ExportRepository
import com.digifender.adminifender.core.data.repository.JournalExport
import com.digifender.adminifender.core.data.repository.MemoryTransferJournal
import com.digifender.adminifender.core.data.repository.PrivateDownloadStore
import java.io.OutputStream
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val SAVED_SOURCE = "00000000-0000-4000-9000-000000000081"
private const val SAVED_ATTACHMENT = "00000000-0000-4000-9000-000000000082"
private const val SAVED_EXPORT = "00000000-0000-4000-9000-000000000083"
private const val SAVED_KEY = "00000000-0000-4000-9000-000000000084"
private const val SAVED_NOW = 1_800_000_000_000L

private class DownloadStateServer : ExportApi, ArchiveApi, ArchiveBytesApi {
    val ready = ArchiveExport(SAVED_EXPORT, "ready", 1, Instant.ofEpochMilli(SAVED_NOW + 60_000).toString(), counts = ExportCounts(0, 0, 0, 0, 0), omissions = emptyList())
    var beforeExport: suspend () -> Unit = {}
    override suspend fun createExport(key: String) = TransferResult.Ok(ready)
    override suspend fun export(id: String): TransferResult<ArchiveExport> { beforeExport(); return TransferResult.Ok(ready) }
    override suspend fun source(id: String) = ReadResult.Ok(SourceDetailDto(
        SourceDto(id = SAVED_SOURCE, kind = "email", state = "ready", admission = "normal", privacy = "hybrid", revision = 1, receivedAt = "2026-10-07T12:00:00Z", subject = "Synthetic"),
        attachments = listOf(AttachmentDto(SAVED_ATTACHMENT, "synthetic.txt", "text/plain", 1, "a".repeat(64), "attachment", "stored")),
    ))
    override suspend fun chunks(id: String, cursor: String?, limit: Int): ReadResult<ChunksPageDto> = error("not used")
    override suspend fun search(request: SearchRequest, cursor: String?, limit: Int): ReadResult<SearchPageDto> = error("not used")
    override suspend fun attachment(id: String, hash: String, size: Long, output: OutputStream): TransferResult<Long> { output.write(1); return TransferResult.Ok(1L) }
    override suspend fun exportContent(id: String, output: OutputStream): TransferResult<Long> = error("not used")
}

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadStateViewModelTest {
    @get:Rule val folder = TemporaryFolder()
    private val viewModels = ViewModelStore()
    private val api = DownloadStateServer()

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() { viewModels.clear(); Dispatchers.resetMain() }

    @Test
    fun delayedExportRefreshCannotRestoreASavedFlagAfterTheZipWasPurged() = runTest {
        val downloads = PrivateDownloadStore(folder.root)
        // This test exercises state observation; ZIP verification is covered by ExportRepositoryTest.
        downloads.export(SAVED_EXPORT, { it.write(1); TransferResult.Ok(1L) }, { true })
        val journal = MemoryTransferJournal()
        journal.putExport(JournalExport(SAVED_KEY, submitted = true, export = api.ready), journal.generation)
        val model = ExportViewModel(ExportRepository(api, api, downloads, { SAVED_NOW }, journal))
        viewModels.put("export", model)
        model.state.first { it.saved && !it.busy }
        val requested = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        api.beforeExport = { requested.complete(Unit); response.await() }
        model.refresh()
        requested.await()
        downloads.clear()
        model.state.first { !it.saved }
        response.complete(Unit)
        val final = model.state.first { !it.busy }
        assertEquals("ready", final.export?.state)
        assertFalse(final.saved)
    }

    @Test
    fun attachmentCompletionAndPurgeUseObservedFileMembership() = runTest {
        val downloads = PrivateDownloadStore(folder.root)
        val model = AttachmentViewModel(SAVED_SOURCE, AttachmentRepository(api, api, downloads))
        viewModels.put("attachment", model)
        model.download(SAVED_ATTACHMENT)
        model.state.first { it[SAVED_ATTACHMENT] == AttachmentDownloadState.SAVED }
        downloads.clear()
        model.state.first { it[SAVED_ATTACHMENT] != AttachmentDownloadState.SAVED }
        assertEquals(AttachmentDownloadState.REMOVED, model.state.value[SAVED_ATTACHMENT])
    }
}
