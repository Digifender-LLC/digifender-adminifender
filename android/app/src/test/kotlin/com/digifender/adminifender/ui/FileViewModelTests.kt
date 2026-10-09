package com.digifender.adminifender.ui

import com.digifender.adminifender.core.data.export.CreateOutcome
import com.digifender.adminifender.core.data.export.ExportDownload
import com.digifender.adminifender.core.data.export.ExportRepository
import com.digifender.adminifender.core.data.extraction.ExtractionScheduler
import com.digifender.adminifender.core.data.extraction.ReadMoreRepository
import com.digifender.adminifender.core.data.files.AttachmentRepository
import com.digifender.adminifender.core.data.files.BlobDirectory
import com.digifender.adminifender.core.data.files.DownloadResult
import com.digifender.adminifender.core.data.files.FileCipher
import com.digifender.adminifender.core.data.files.FileState
import com.digifender.adminifender.core.data.network.ConfirmResult
import com.digifender.adminifender.core.data.network.CreateExportResult
import com.digifender.adminifender.core.data.network.ExportAdmission
import com.digifender.adminifender.core.data.network.ExportRead
import com.digifender.adminifender.core.data.network.ExportScope
import com.digifender.adminifender.core.data.network.ExportState
import com.digifender.adminifender.core.data.network.OpenResult
import com.digifender.adminifender.core.data.network.PrepareResult
import com.digifender.adminifender.core.data.network.ReadMoreApi
import com.digifender.adminifender.core.data.network.ReadMoreOutcome
import com.digifender.adminifender.core.data.network.ReviewRead
import com.digifender.adminifender.core.data.repository.ArchiveRepository
import com.digifender.adminifender.core.data.repository.AttachmentInfo
import com.digifender.adminifender.core.data.repository.CacheSource
import com.digifender.adminifender.core.data.review.DeletionPhase
import com.digifender.adminifender.core.data.review.DeletionRepository
import com.digifender.adminifender.core.data.review.RecentAuthenticator
import com.digifender.adminifender.core.data.session.PasskeyAuthenticator
import com.digifender.adminifender.core.data.session.PasskeyResult
import com.digifender.adminifender.core.data.session.RecentAuthOutcome
import com.digifender.adminifender.core.data.sync.QueuedMutation
import com.digifender.adminifender.core.model.Concern
import java.io.File
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private val noConcernsCache = object : CacheSource {
    override fun concerns(): Flow<List<Concern>> = flowOf(emptyList())
    override fun mutations(): Flow<List<QueuedMutation>> = flowOf(emptyList())
    override fun syncState(): Flow<Pair<Long?, Boolean>> = flowOf(null to true)
}

private suspend fun eventually(timeoutMs: Long = 5_000, check: () -> Boolean) {
    withTimeout(timeoutMs) { while (!check()) delay(10) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentsViewModelTest {
    private val src = uuid(31)
    private val att = uuid(32)
    private val sources = mutableMapOf(src to source(src))
    private val store = InMemoryFileStore { sources }
    private val dir = tempDirectory()
    private val blobs = BlobDirectory(File(dir, "blobs"))
    private val api = ScriptedContentApi()
    private val scheduler = FakeScheduler()
    private val repo = AttachmentRepository(api, store, blobs, FileCipher(SoftKeys()), scheduler, { System.currentTimeMillis() })
    private var readMoreAnswer: ReadMoreOutcome = ReadMoreOutcome.Requested(101)
    private val readMoreCalls = mutableListOf<String>()
    private var syncs = 0
    private var extractionRuns = 0
    private val readMore = ReadMoreRepository(
        object : ReadMoreApi {
            override suspend fun readMore(attachmentId: String): ReadMoreOutcome {
                readMoreCalls += attachmentId
                return readMoreAnswer
            }
        },
        object : ExtractionScheduler {
            override fun requestNow() {
                extractionRuns += 1
            }

            override fun schedulePeriodic() = Unit

            override fun cancel() = Unit
        },
    ) { syncs += 1 }
    private val text = "hello <b>plain</b>".encodeToByteArray()
    private fun info(type: String? = "text/plain", name: String = "note.txt", bytes: ByteArray = text, size: Long = bytes.size.toLong(), sha: String = sha256Of(bytes)) =
        AttachmentInfo(att, name, type, size, "ready", null, sha, "attachment")

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    @Test
    fun readingMoreQueuesTheNextPagesAndSaysSoWithoutChangingAnythingSearchable() = runBlocking {
        val model = AttachmentsViewModel(src, repo, readMore)
        val job = launch(Dispatchers.Unconfined) { model.state.collect {} }
        model.readMore(info())
        eventually { model.state.value.notice == AttachmentNotice.READ_MORE_ASKED }
        assertEquals(listOf(att), readMoreCalls)
        // A sync is asked for (so the file shows as waiting) and the reader is scheduled.
        assertEquals(1, syncs)
        assertEquals(1, extractionRuns)
        assertEquals("note.txt", model.state.value.noticeName)
        job.cancel()
    }

    @Test
    fun readingMoreSaysWhyWhenItCannotBeQueuedAndSchedulesNothing() = runBlocking {
        val model = AttachmentsViewModel(src, repo, readMore)
        val job = launch(Dispatchers.Unconfined) { model.state.collect {} }
        for ((answer, expected) in listOf(
            ReadMoreOutcome.NothingToRead to AttachmentNotice.READ_MORE_NOTHING,
            ReadMoreOutcome.NotAvailable("not_eligible") to AttachmentNotice.SOURCE_GONE,
            ReadMoreOutcome.SessionExpired to AttachmentNotice.READ_MORE_SIGN_IN,
            ReadMoreOutcome.Unavailable("network") to AttachmentNotice.READ_MORE_UNAVAILABLE,
        )) {
            readMoreAnswer = answer
            model.noticeShown()
            model.readMore(info())
            eventually { model.state.value.notice == expected }
        }
        assertEquals(0, syncs)
        assertEquals(0, extractionRuns)
        job.cancel()
    }

    @Test
    fun askingStoresTheRequestAndRefusalsAreSaidNotSwallowed() = runBlocking {
        val model = AttachmentsViewModel(src, repo, readMore)
        val job = launch(Dispatchers.Unconfined) { model.state.collect {} }
        model.ask(info(size = 26L * 1024 * 1024))
        eventually { model.state.value.notice == AttachmentNotice.TOO_LARGE }
        assertEquals("note.txt", model.state.value.noticeName)
        model.noticeShown()
        model.ask(info(sha = ""))
        eventually { model.state.value.notice == AttachmentNotice.NEEDS_REFRESH }
        assertTrue(model.state.value.files.isEmpty())
        model.noticeShown()
        model.ask(info())
        eventually { model.state.value.files[att]?.state == FileState.QUEUED }
        assertNull(model.state.value.notice)
        assertEquals(listOf(att), scheduler.enqueued)
        job.cancel()
    }

    @Test
    fun aDownloadedTextFileIsShownAsPlainTextAndOtherFormatsAreNotOpened() = runBlocking {
        val model = AttachmentsViewModel(src, repo, readMore)
        val job = launch(Dispatchers.Unconfined) { model.state.collect {} }
        model.ask(info())
        api.serve(text)
        assertEquals(DownloadResult.Done, repo.run(att))
        eventually { model.state.value.files[att]?.state == FileState.READY }
        model.show(info())
        eventually { model.state.value.preview != null }
        // Markup in the file is text here, never rendered.
        assertEquals("hello <b>plain</b>", model.state.value.preview?.text)
        model.closePreview()
        eventually { model.state.value.preview == null }
        model.show(info(type = "text/html", name = "page.html"))
        eventually { model.state.value.notice == AttachmentNotice.NOT_SHOWN }
        assertNull(model.state.value.preview)
        model.remove(att)
        eventually { model.state.value.files.isEmpty() }
        assertTrue(blobs.names().isEmpty())
        job.cancel()
    }

    @Test
    fun savingACopyNamesWhereItWentAndWhetherAFailedOneWasRemoved() = runBlocking {
        val model = AttachmentsViewModel(src, repo, readMore)
        val job = launch(Dispatchers.Unconfined) { model.state.collect {} }
        model.ask(info())
        api.serve(text)
        repo.run(att)
        val target = File(dir, "copy.txt")
        model.saveCopy(info(), FileDestination(target, label = "copy.txt"))
        eventually { model.state.value.notice == AttachmentNotice.SAVED }
        assertEquals("copy.txt", model.state.value.noticeName)
        assertEquals(text.toList(), target.readBytes().toList())
        model.noticeShown()
        model.saveCopy(info(), FileDestination(File(dir, "x.txt"), label = "x.txt", failWritesAfter = 2))
        eventually { model.state.value.notice == AttachmentNotice.SAVE_FAILED }
        model.noticeShown()
        model.saveCopy(info(), FileDestination(File(dir, "y.txt"), label = "y.txt", failWritesAfter = 2, refuseDiscard = true))
        eventually { model.state.value.notice == AttachmentNotice.SAVE_LEFTOVER }
        job.cancel()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DeletionViewModelTest {
    private val a = uuid(41)
    private val b = uuid(42)
    private val api = FakeReviewApi()
    private val store = InMemoryDeletionStore()
    private val cache = FakeArchive().also { it.held.value = listOf(a, b).associateWith { id -> source(id) } }
    private val purge = RecordingPurge()
    private var auth: suspend () -> RecentAuthOutcome = { RecentAuthOutcome.Confirmed(1) }
    private var ids = 0
    private val repo = DeletionRepository(api, store, cache, purge, RecentAuthenticator { auth() }, { 1_000L }, { "id-${ids++}" })
    private val archive = ArchiveRepository(object : com.digifender.adminifender.core.data.network.ArchiveApi {
        override suspend fun source(id: String) = com.digifender.adminifender.core.data.network.ReadResult.Unavailable("network")
        override suspend fun chunks(id: String, cursor: String?, limit: Int) = com.digifender.adminifender.core.data.network.ReadResult.Unavailable("network")
        override suspend fun search(request: com.digifender.adminifender.core.data.network.SearchRequest, cursor: String?, limit: Int) = com.digifender.adminifender.core.data.network.ReadResult.Unavailable("network")
    }, cache, noConcernsCache) { 1_000L }
    private val passkeys = PasskeyAuthenticator { PasskeyResult.Cancelled }

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    private fun model(vararg selected: String) = DeletionViewModel(DeletionTarget.Selection(selected.toList()), repo, archive)

    @Test
    fun theScreenShowsTheServersExactReviewAndComingBackAdoptsItInsteadOfPreparingAgain() = runBlocking {
        api.onPrepare = { _, requested -> preparedFor(requested) }
        val first = model(a, b)
        val job = launch(Dispatchers.Unconfined) { first.state.collect {} }
        eventually { first.state.value.review?.phase == DeletionPhase.PREPARED }
        assertEquals(2, first.state.value.items.size)
        assertEquals("Subject $a", first.state.value.items.first { it.sourceId == a }.subject)
        job.cancel()
        // The same selection opened again (rotation, process restore) uses the stored review.
        val again = model(b, a)
        val job2 = launch(Dispatchers.Unconfined) { again.state.collect {} }
        eventually { again.state.value.review?.phase == DeletionPhase.PREPARED }
        assertEquals(1, api.prepareCalls.size)
        job2.cancel()
    }

    @Test
    fun anOversizedOrUnknownSelectionIsRefusedWithAReasonAndNothingIsSent() = runBlocking {
        val tooMany = DeletionViewModel(DeletionTarget.Selection((1..51).map { uuid(100 + it) }), repo, archive)
        val job = launch(Dispatchers.Unconfined) { tooMany.state.collect {} }
        eventually { tooMany.state.value.note == DeletionNote.TOO_MANY }
        assertEquals("50", tooMany.state.value.noteCode)
        val missing = model(uuid(77))
        val job2 = launch(Dispatchers.Unconfined) { missing.state.collect {} }
        eventually { missing.state.value.note == DeletionNote.NOT_ON_PHONE }
        assertTrue(api.prepareCalls.isEmpty())
        job.cancel()
        job2.cancel()
    }

    @Test
    fun confirmingAsksForThePasskeyAndAnUnansweredConfirmationStaysUnknownUntilRead() = runBlocking {
        api.onPrepare = { _, requested -> preparedFor(requested) }
        val vm = model(a)
        val job = launch(Dispatchers.Unconfined) { vm.state.collect {} }
        eventually { vm.state.value.review?.phase == DeletionPhase.PREPARED }
        auth = { RecentAuthOutcome.Cancelled }
        vm.confirm(passkeys)
        eventually { vm.state.value.note == DeletionNote.AUTH_CANCELLED }
        assertTrue(api.confirmCalls.isEmpty())
        auth = { RecentAuthOutcome.Confirmed(1) }
        api.onConfirm = { _, _, _ -> ConfirmResult.NoAnswer("network") }
        vm.confirm(passkeys)
        eventually { vm.state.value.note == DeletionNote.UNKNOWN }
        assertEquals(DeletionPhase.SENDING, vm.state.value.review?.phase)
        // Checking only reads; a 404 is not proof of anything.
        api.onReadReview = { ReviewRead.NotKnown }
        vm.checkStatus()
        eventually { vm.state.value.note == DeletionNote.READ_NOT_KNOWN }
        assertEquals(1, api.confirmCalls.size)
        assertEquals(DeletionPhase.SENDING, vm.state.value.review?.phase)
        job.cancel()
    }

    @Test
    fun aSuccessfulDeletionShowsTheReceiptAndThePurgeHappened() = runBlocking {
        api.onPrepare = { _, requested -> preparedFor(requested) }
        val vm = model(a)
        val job = launch(Dispatchers.Unconfined) { vm.state.collect {} }
        eventually { vm.state.value.review?.phase == DeletionPhase.PREPARED }
        api.onConfirm = { _, _, _ -> confirmed(listOf(a), uuid(950)) }
        vm.confirm(passkeys)
        eventually { vm.state.value.review?.phase == DeletionPhase.SUCCEEDED }
        assertEquals(DeletionNote.DELETED, vm.state.value.note)
        assertEquals(listOf(listOf(a)), purge.purged)
        assertNull(vm.state.value.review?.cleanup)
        job.cancel()
    }

    @Test
    fun anUnansweredPreparationCanBeAskedAgainAndAReviewCanBeRedoneOnPurpose() = runBlocking {
        val vm = model(a)
        val job = launch(Dispatchers.Unconfined) { vm.state.collect {} }
        eventually { vm.state.value.note == DeletionNote.WAITING }
        assertEquals(DeletionPhase.PREPARING, vm.state.value.review?.phase)
        api.onPrepare = { _, requested -> preparedFor(requested) }
        vm.retryPrepare()
        eventually { vm.state.value.review?.phase == DeletionPhase.PREPARED }
        assertEquals(api.prepareCalls[0].first, api.prepareCalls[1].first)
        api.onConfirm = { _, _, _ -> ConfirmResult.Rejected("stale_review", 409) }
        vm.confirm(passkeys)
        eventually { vm.state.value.review?.phase == DeletionPhase.REJECTED }
        assertEquals(DeletionNote.REJECTED, vm.state.value.note)
        vm.prepareAgain()
        eventually { vm.state.value.review?.phase == DeletionPhase.PREPARED && api.prepareCalls.size == 3 }
        assertNotNull(vm.state.value.review?.reviewId)
        job.cancel()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ExportBuilderViewModelTest {
    private val api = FakeExportApi()
    private val store = InMemoryExportStore()
    private val repo = ExportRepository(api, store, RecordingExportScheduler(), { 1_000L }, { "k-${store.rows.value.size}" })

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    @Test
    fun theFormNeverImpliesQuarantineAndExplainsWhatItCannotSend() = runBlocking {
        val model = ExportBuilderViewModel(emptyList(), repo, ZoneOffset.UTC)
        val job = launch(Dispatchers.Unconfined) { model.state.collect {} }
        assertEquals(ExportAdmission.NORMAL, model.state.value.admission)
        assertEquals(ExportKind.ARCHIVE, model.state.value.kind)
        assertNull(model.state.value.problem)
        model.kind(ExportKind.FILTER)
        assertEquals(BuilderProblem.EMPTY_FILTER, model.state.value.problem)
        model.from("2026-13-40")
        assertEquals(BuilderProblem.BAD_FROM_DATE, model.state.value.problem)
        model.from("2026-10-01")
        model.to("2026-09-01")
        assertEquals(BuilderProblem.BAD_RANGE, model.state.value.problem)
        model.to("2026-11-01")
        model.sender("Has Space")
        assertEquals(BuilderProblem.BAD_ADDRESS, model.state.value.problem)
        model.sender("Boss@Example.com")
        assertNull(model.state.value.problem)
        val scope = model.scopeOf() as ExportScope.Filtered
        // Midnight UTC for the chosen zone, inclusive start and exclusive end.
        assertEquals(1_790_812_800_000L, scope.filter.fromMs)
        assertEquals(1_793_491_200_000L, scope.filter.toMs)
        // Addresses are matched exactly in lower case, so the form lower-cases what was typed.
        assertEquals("boss@example.com", scope.filter.sender)
        model.admission(ExportAdmission.ALL)
        assertEquals(ExportAdmission.ALL, model.scopeOf()?.admission)
        job.cancel()
    }

    @Test
    fun aSelectionIsSentWholeOrNotAtAll() = runBlocking {
        val model = ExportBuilderViewModel((1..1001).map { uuid(it) }, repo, ZoneOffset.UTC)
        val job = launch(Dispatchers.Unconfined) { model.state.collect {} }
        assertEquals(ExportKind.SELECTED, model.state.value.kind)
        assertEquals(BuilderProblem.TOO_MANY_SOURCES, model.state.value.problem)
        model.create()
        assertTrue(api.creates.isEmpty())
        val ok = ExportBuilderViewModel((1..1000).map { uuid(it) }, repo, ZoneOffset.UTC)
        val job2 = launch(Dispatchers.Unconfined) { ok.state.collect {} }
        assertNull(ok.state.value.problem)
        api.onCreate = { _, scope -> CreateExportResult.Created(ExportStatusFor(scope)) }
        ok.create()
        eventually { ok.state.value.created != null }
        assertEquals(1000, (api.creates.single().second as ExportScope.Sources).sourceIds.size)
        job.cancel()
        job2.cancel()
    }

    @Test
    fun anUnansweredRequestStillOpensTheStoredExportWithAWarning() = runBlocking {
        val model = ExportBuilderViewModel(emptyList(), repo, ZoneOffset.UTC)
        val job = launch(Dispatchers.Unconfined) { model.state.collect {} }
        model.create()
        eventually { model.state.value.created != null }
        assertEquals(CreateNote.WAITING, model.state.value.note)
        assertEquals(1, store.all().size)
        job.cancel()
    }
}

private fun ExportStatusFor(scope: ExportScope) = com.digifender.adminifender.core.data.network.ExportStatus(
    EXPORT_ID, ExportState.PENDING, scope, 41, EXPORT_CREATED, EXPORT_CREATED + 1000, null, null, null, null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ExportViewModelTest {
    private val api = FakeExportApi()
    private val store = InMemoryExportStore()
    private val repo = ExportRepository(api, store, RecordingExportScheduler(), { 1_000L }, { "k-${store.rows.value.size}" })
    private val built = buildExport()

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    private suspend fun created(): String {
        api.onCreate = { _, scope -> CreateExportResult.Created(ExportStatusFor(scope)) }
        return (repo.create(defaultScope()) as CreateOutcome.Created).intent.localId
    }

    @Test
    fun theForegroundRefreshStopsWhenTheExportSettlesAndIsBoundedWhenItNeverDoes() = runTest {
        val id = created()
        var calls = 0
        api.onStatus = { calls++; if (calls < 3) ExportRead.Ok(ExportStatusFor(defaultScope())) else ExportRead.Ok(built.status) }
        val model = ExportViewModel(id, repo) { }
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        model.onForeground()
        advanceTimeBy(60_000)
        assertEquals(3, calls)
        assertEquals(ExportState.READY, model.state.value.intent?.state)
        model.onBackground()
        // A different export that never settles is polled a bounded number of times, not forever.
        val other = created()
        calls = 0
        api.onStatus = { calls++; ExportRead.Ok(ExportStatusFor(defaultScope()).copy(id = store.get(other)!!.exportId ?: EXPORT_ID)) }
        val endless = ExportViewModel(other, repo) { }
        val job2 = launch(UnconfinedTestDispatcher(testScheduler)) { endless.state.collect {} }
        endless.onForeground()
        advanceTimeBy(EXPORT_POLL_DELAYS_MS.sum() + 10_000)
        assertEquals(EXPORT_POLL_DELAYS_MS.size, calls)
        job.cancel()
        job2.cancel()
        endless.onBackground()
    }

    @Test
    fun theDownloadRunsInTheAppsOwnScopeNotTheScreens() = runTest {
        val id = created()
        api.onStatus = { ExportRead.Ok(built.status) }
        repo.refresh(id)
        api.onOpen = { contentOf(built.bytes).second }
        val launched = mutableListOf<suspend () -> Unit>()
        val model = ExportViewModel(id, repo) { launched += it }
        val file = File(tempDirectory(), "x.zip")
        model.download(FileDestination(file))
        // Nothing ran on the view model's scope; the app's runner got it.
        assertEquals(1, launched.size)
        assertFalse(file.exists())
        launched.single().invoke()
        eventuallyBlocking { store.rows.value[id]?.download == ExportDownload.VERIFIED }
        assertTrue(file.exists())
    }

    private fun eventuallyBlocking(check: () -> Boolean) = runBlocking { eventually { check() } }
}
