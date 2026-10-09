package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.export.CreateOutcome
import com.digifender.adminifender.core.data.export.ExportDownload
import com.digifender.adminifender.core.data.export.ExportDownloadOutcome
import com.digifender.adminifender.core.data.export.ExportRepository
import com.digifender.adminifender.core.data.files.AttachmentRepository
import com.digifender.adminifender.core.data.files.BlobDirectory
import com.digifender.adminifender.core.data.files.DownloadResult
import com.digifender.adminifender.core.data.files.FileCipher
import com.digifender.adminifender.core.data.files.SessionEnd
import com.digifender.adminifender.core.data.network.CreateExportResult
import com.digifender.adminifender.core.data.network.ExportRead
import com.digifender.adminifender.core.data.network.ExportState
import com.digifender.adminifender.core.data.network.InvalidatedReason
import com.digifender.adminifender.core.data.network.OpenResult
import com.digifender.adminifender.core.data.network.ContentStream
import com.digifender.adminifender.core.data.repository.AttachmentInfo
import com.digifender.adminifender.core.data.review.DeletionPhase
import com.digifender.adminifender.core.data.session.SessionState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val SRC = uuid(21)
private val ATT = uuid(22)

/**
 * What happens to work that is actually running when the session ends or sync takes an email away: the connections are
 * aborted and the scheduler told (not merely the rows removed), and the owner's durable requests survive an expired session.
 */
class SessionLifecycleTest {
    private val sources = mutableMapOf(SRC to source(SRC))
    private val files = InMemoryFileStore { sources }
    private val dir = tempDirectory()
    private val blobs = BlobDirectory(File(dir, "blobs"))
    private val contentApi = ScriptedContentApi()
    private val scheduler = FakeScheduler()
    private val attachments = AttachmentRepository(contentApi, files, blobs, FileCipher(SoftKeys()), scheduler, { System.currentTimeMillis() })
    private val exportApi = FakeExportApi()
    private val exportStore = InMemoryExportStore()
    private val exportScheduler = RecordingExportScheduler()
    private val exports = ExportRepository(exportApi, exportStore, exportScheduler, { System.currentTimeMillis() })
    private val built = buildExport()
    private val body = ByteArray(300_000) { (it % 251).toByte() }

    private fun info() = AttachmentInfo(ATT, "a.txt", "text/plain", body.size.toLong(), "ready", null, sha256Of(body), "attachment")

    private suspend fun readyExport(): String {
        exportApi.onCreate = { _, scope -> CreateExportResult.Created(built.status.copy(state = ExportState.PENDING, counts = null, omissions = null, scope = scope)) }
        val id = (exports.create(built.status.scope!!) as CreateOutcome.Created).intent.localId
        exportApi.onStatus = { ExportRead.Ok(built.status) }
        exports.refresh(id)
        return id
    }

    @Test
    fun syncRemovingAnEmailAbortsItsRunningDownloadAndCancelsItsWorkNotJustItsRow() = runBlocking {
        attachments.ask(SRC, info())
        val stream = contentApi.serve(body, gateAfter = 100_000)
        val running = async(Dispatchers.Default) { attachments.run(ATT) }
        stream.reachedGate.await()
        // What RoomLocalStore does in the transaction that applies a tombstone: the row goes, then the app is told.
        sources.remove(SRC)
        val dropped = files.purgeSources(listOf(SRC))
        attachments.stopDownloads(listOf(ATT))
        assertEquals(emptyList<String>(), dropped)
        assertTrue("the connection was closed, not left to finish", withTimeout(10_000) { while (!stream.aborted) delay(10); true })
        assertTrue(ATT in scheduler.cancelled)
        assertEquals(DownloadResult.Superseded, withTimeout(10_000) { running.await() })
        assertNull(files.get(ATT))
        assertTrue(blobs.names().isEmpty())
    }

    @Test
    fun withoutTheStopSignalTheGenerationGuardStillRefusesTheCommit() = runBlocking {
        attachments.ask(SRC, info())
        val stream = contentApi.serve(body, gateAfter = 100_000)
        val running = async(Dispatchers.Default) { attachments.run(ATT) }
        stream.reachedGate.await()
        sources.remove(SRC)
        files.purgeSources(listOf(SRC))
        stream.release()
        assertEquals(DownloadResult.Superseded, withTimeout(10_000) { running.await() })
        assertTrue(scheduler.cancelled.isEmpty())
        assertNull(files.get(ATT))
        assertTrue(blobs.names().isEmpty())
    }

    @Test
    fun anEndedSessionAbortsBothAnAttachmentAndAnExportTransferButKeepsTheOwnersRequests() = runBlocking {
        // A running attachment download.
        attachments.ask(SRC, info())
        val attachmentStream = contentApi.serve(body, gateAfter = 100_000)
        val attachmentRun = async(Dispatchers.Default) { attachments.run(ATT) }
        attachmentStream.reachedGate.await()
        // A running export download.
        val exportId = readyExport()
        val exportStream = ScriptedStream(built.bytes, gateAfter = built.bytes.size / 2)
        exportApi.onOpen = { OpenResult.Open(ContentStream(built.bytes.size.toLong(), null, exportStream, exportStream::abort)) }
        val target = File(dir, "export.zip")
        val destination = FileDestination(target)
        val exportRun = async(Dispatchers.Default) { exports.download(exportId, destination) }
        exportStream.reachedGate.await()
        // The owner's durable requests that must survive an expired session: an unknown deletion and the export intent itself.
        val deletions = InMemoryDeletionStore()
        deletions.put(
            com.digifender.adminifender.core.data.review.DeletionReview(
                "d1", listOf(SRC), "k1", DeletionPhase.SENDING, "network", uuid(900), DIGEST, 10, mapOf(SRC to 1), emptyMap(), null, "k2", null, null, null, null, null, 1, 1,
            ),
        )

        val states = MutableStateFlow<SessionState>(SessionState.SignedIn("device"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            SessionEnd(attachments, exports).watch(scope, states)
            delay(100)
            assertFalse(attachmentStream.aborted)
            // The server refuses the session: sign-in is required, nothing is signed out by the owner.
            states.value = SessionState.SignInRequired
            assertTrue(withTimeout(10_000) { while (!attachmentStream.aborted || !exportStream.aborted) delay(10); true })
        } finally {
            scope.coroutineContext[Job]?.cancel()
        }
        assertEquals(DownloadResult.Superseded, withTimeout(10_000) { attachmentRun.await() })
        val finished = withTimeout(10_000) { exportRun.await() }
        assertTrue(finished is ExportDownloadOutcome.Superseded)
        // Files: rows and bytes gone, queued work cancelled.
        assertTrue(files.all().isEmpty())
        assertTrue(blobs.names().isEmpty())
        assertTrue(scheduler.cancelledAll >= 1)
        // The export transfer: partial file discarded, the interruption recorded, the intent kept.
        assertFalse(target.exists())
        assertTrue(destination.discarded)
        val intent = exportStore.get(exportId)!!
        assertEquals(ExportDownload.INTERRUPTED, intent.download)
        assertEquals("cancelled", intent.downloadError)
        assertEquals(true, intent.destinationDiscarded)
        assertNull(intent.verification)
        assertEquals(ExportState.READY, intent.state)
        assertNotNull(deletions.get("d1"))
        assertEquals(DeletionPhase.SENDING, deletions.get("d1")!!.phase)
    }

    @Test
    fun anEndedSessionAlsoStopsDeviceExtractionAndRemovesItsDownloads() = runBlocking {
        val work = File(dir, "extraction").also { it.mkdirs() }
        val leftover = File(work, "lease.part").also { it.writeText("document bytes") }
        val fake = FakeWorker(pretendWork(PretendFile()))
        val extractor = com.digifender.adminifender.core.data.extraction.DeviceExtractor(
            fake, fake, opener(com.digifender.adminifender.core.data.extraction.OpenOutcome.Unsupported),
            com.digifender.adminifender.core.data.extraction.ExtractionCapabilities(true, true, "android-extract-v1"), { null }, work, { 0L }, {},
        )
        var cancelled = 0
        val extractionScheduler = object : com.digifender.adminifender.core.data.extraction.ExtractionScheduler {
            override fun requestNow() = Unit

            override fun schedulePeriodic() = Unit

            override fun cancel() {
                cancelled += 1
            }
        }
        SessionEnd(attachments, exports, extractor, extractionScheduler).ended()
        assertEquals(1, cancelled)
        assertFalse(leftover.exists())
    }

    @Test
    fun explicitSignOutFencesLateAnswersAsWell() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<CreateExportResult>()
        exportApi.onCreate = { _, _ -> gate.await() }
        val creating = async { exports.create(built.status.scope!!) }
        while (exportStore.all().isEmpty()) kotlinx.coroutines.yield()
        exports.invalidate()
        exportStore.rows.value = emptyMap()
        gate.complete(CreateExportResult.Created(built.status))
        assertEquals(CreateOutcome.Superseded, creating.await())
        assertTrue(exportStore.all().isEmpty())
    }

    @Test
    fun anExportThatTurnsInvalidWhileItDownloadsIsVerifiedAsAFileButStillReadsAsInvalidated() = runBlocking {
        val exportId = readyExport()
        var calls = 0
        exportApi.onStatus = {
            // Ready for the pre-download read, invalidated once the file is complete.
            if (calls++ == 0) ExportRead.Ok(built.status) else ExportRead.Ok(built.status.copy(state = ExportState.INVALIDATED, counts = null, omissions = null, invalidatedReason = InvalidatedReason.DELETED))
        }
        exportApi.onOpen = { OpenResult.Open(ScriptedStream(built.bytes).let { s -> ContentStream(built.bytes.size.toLong(), null, s, s::abort) }) }
        val out = exports.download(exportId, FileDestination(File(dir, "late.zip"))) as ExportDownloadOutcome.Verified
        assertEquals(ExportDownload.VERIFIED, out.intent.download)
        val now = exportStore.get(exportId)!!
        assertEquals(ExportDownload.VERIFIED, now.download)
        assertEquals(ExportState.INVALIDATED, now.state)
        assertEquals(InvalidatedReason.DELETED, now.status?.invalidatedReason)
    }
}
