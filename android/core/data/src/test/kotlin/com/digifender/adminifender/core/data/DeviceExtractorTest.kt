package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.extraction.DeviceExtractor
import com.digifender.adminifender.core.data.extraction.ExtractionCapabilities
import com.digifender.adminifender.core.data.extraction.OpenOutcome
import com.digifender.adminifender.core.data.extraction.RunConditions
import com.digifender.adminifender.core.data.extraction.RunResult
import com.digifender.adminifender.core.data.extraction.sha256Hex
import com.digifender.adminifender.core.data.network.BatchResult
import com.digifender.adminifender.core.data.network.BatchStatus
import com.digifender.adminifender.core.data.network.ClaimResult
import com.digifender.adminifender.core.data.network.DeviceKind
import com.digifender.adminifender.core.data.network.ExtractionOutcome
import com.digifender.adminifender.core.data.network.HeartbeatResult
import com.digifender.adminifender.core.data.network.NoWorkReason
import com.digifender.adminifender.core.data.network.OpenResult
import com.digifender.adminifender.core.data.network.PageMethod
import com.digifender.adminifender.core.data.network.SubmitResult
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val LONG_TEXT = "Invoice 7731 for the quarterly storage plan is due on Friday."

class DeviceExtractorTest {
    private lateinit var dir: File
    private var now = 1_000_000L
    private var stopReason: String? = null
    private val pauses = mutableListOf<Long>()
    private val file = PretendFile()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("extractor").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun extractor(
        worker: FakeWorker,
        pages: FakePages?,
        capabilities: ExtractionCapabilities = ExtractionCapabilities(pdfText = true, ocr = true, extractorVersion = "android-extract-v1"),
        open: OpenOutcome? = null,
    ) = DeviceExtractor(
        api = worker,
        content = worker,
        opener = opener(open ?: OpenOutcome.Opened(checkNotNull(pages))),
        capabilities = capabilities,
        conditions = RunConditions { stopReason },
        workDir = File(dir, "extraction"),
        clock = { now },
        pause = { pauses += it },
    ).also { worker.file = file }

    private fun leftovers() = File(dir, "extraction").listFiles().orEmpty().toList()

    private fun workerFor(kind: DeviceKind = DeviceKind.PDF, ocrAllowed: Boolean = true) = FakeWorker(pretendWork(file, kind, ocrAllowed))

    @Test
    fun aPdfWithATextLayerIsSentAsOneFinalBatchAndPublished() = runBlocking {
        val worker = workerFor()
        val pages = FakePages(3, native = mapOf(1 to LONG_TEXT, 2 to "$LONG_TEXT two", 3 to "$LONG_TEXT three"))
        val result = extractor(worker, pages).runOnce()
        assertEquals(RunResult.Published(uuid(23), limited = false), result)
        assertEquals(1, worker.batches.size)
        val batch = worker.batches.single()
        assertTrue(batch.complete)
        assertEquals(3, batch.lastPage)
        assertEquals(listOf(PageMethod.PDF_TEXT), batch.pages.map { it.method }.distinct())
        assertEquals(sha256Hex(ByteArray(0)), batch.manifestHash)
        assertNull(batch.totalPages)
        assertTrue(pages.recognizeCalls.isEmpty())
        assertTrue(pages.closed)
        assertTrue("the downloaded file is removed", leftovers().isEmpty())
        assertEquals("the capabilities are what the claim states", listOf(true to true), worker.claimRequests.map { it.pdfText to it.ocr })
    }

    @Test
    fun aLongDocumentGoesInBatchesOfTenPagesAndTheLastOneCarriesTheManifest() = runBlocking {
        val worker = workerFor()
        val pages = FakePages(25, native = (1..25).associateWith { "$LONG_TEXT page $it" })
        assertEquals(RunResult.Published(uuid(23), false), extractor(worker, pages).runOnce())
        assertEquals(listOf(0 to 10, 1 to 20, 2 to 25), worker.batches.map { it.ordinal to it.lastPage })
        assertEquals(listOf(false, false, true), worker.batches.map { it.complete })
        assertEquals((1..25).toList(), worker.accepted.keys.toList())
    }

    @Test
    fun recognitionRunsOnlyOnPagesWithoutAUsableTextLayer() = runBlocking {
        val worker = workerFor()
        val pages = FakePages(
            4,
            native = mapOf(1 to LONG_TEXT, 2 to null, 3 to "  ", 4 to "p4"),
            recognized = mapOf(2 to "Scanned receipt total 42.00 EUR", 3 to null, 4 to "Page four recognized text"),
        )
        extractor(worker, pages).runOnce()
        assertEquals("only pages without meaningful text are recognized", listOf(2, 3, 4), pages.recognizeCalls)
        assertEquals(PageMethod.PDF_TEXT, worker.accepted.getValue(1).method)
        assertEquals(PageMethod.OCR, worker.accepted.getValue(2).method)
        assertNull("a blank page produces no text", worker.accepted[3])
        // A thin text layer is replaced by recognition when recognition finds more.
        assertEquals("Page four recognized text", worker.accepted.getValue(4).text)
        assertEquals(PageMethod.OCR, worker.accepted.getValue(4).method)
    }

    @Test
    fun withoutPermissionToRecognizeNothingIsRecognizedAndAThinTextLayerIsKept() = runBlocking {
        val worker = workerFor(ocrAllowed = false)
        val pages = FakePages(3, native = mapOf(1 to LONG_TEXT, 2 to null, 3 to "p3"), recognized = mapOf(2 to "must never be read"))
        val result = extractor(worker, pages).runOnce()
        assertEquals(RunResult.Published(uuid(23), false), result)
        assertTrue(pages.recognizeCalls.isEmpty())
        assertEquals(listOf(1, 3), worker.accepted.keys.toList())
        assertEquals("p3", worker.accepted.getValue(3).text)
    }

    @Test
    fun anImageIsRecognizedAndItsNonexistentTextLayerIsNeverAsked() = runBlocking {
        val worker = workerFor(kind = DeviceKind.IMAGE)
        val pages = FakePages(1, native = mapOf(1 to "must not be used"), recognized = mapOf(1 to "Whiteboard: ship on Monday"))
        assertEquals(RunResult.Published(uuid(23), false), extractor(worker, pages).runOnce())
        assertTrue(pages.nativeCalls.isEmpty())
        assertEquals(PageMethod.OCR, worker.accepted.getValue(1).method)
        assertEquals("Whiteboard: ship on Monday", worker.accepted.getValue(1).text)
    }

    @Test
    fun aPhoneWithoutATextLayerApiRecognizesPdfPages() = runBlocking {
        val worker = workerFor()
        val pages = FakePages(2, native = mapOf(1 to LONG_TEXT), recognized = mapOf(1 to "Recognized page one", 2 to "Recognized page two"))
        extractor(worker, pages, ExtractionCapabilities(pdfText = false, ocr = true, extractorVersion = "android-extract-v1")).runOnce()
        assertTrue(pages.nativeCalls.isEmpty())
        assertEquals(listOf(PageMethod.OCR, PageMethod.OCR), worker.accepted.values.map { it.method })
    }

    @Test
    fun aPhoneThatCanDoNeitherDoesNotEvenClaim() = runBlocking {
        val worker = workerFor()
        val result = extractor(worker, null, ExtractionCapabilities(pdfText = false, ocr = false, extractorVersion = "android-extract-v1"), open = OpenOutcome.Unsupported).runOnce()
        assertEquals(RunResult.Idle(NoWorkReason.NO_CAPABILITY), result)
        assertTrue(worker.claimRequests.isEmpty())
    }

    @Test
    fun aLongerDocumentIsReadUpToTheLimitAndSaysSo() = runBlocking {
        val worker = workerFor()
        val pages = FakePages(150, native = (1..150).associateWith { "$LONG_TEXT page $it" })
        val result = extractor(worker, pages).runOnce()
        assertEquals(RunResult.Published(uuid(23), limited = true), result)
        assertEquals(100, worker.batches.last().lastPage)
        assertEquals(150, worker.batches.last().totalPages)
        assertEquals(100, worker.accepted.size)
        assertEquals("pages past the limit are never even read", (1..100).toList(), pages.nativeCalls)
    }

    @Test
    fun aReadTheOwnerAskedToContinueStartsAfterThePublishedPagesAndStopsAHundredLater() = runBlocking {
        val hashes = (1..10).map { sha256Hex("batch$it".toByteArray()) }
        val worker = FakeWorker(pretendWork(file, startPage = 101, nextBatchOrdinal = 10, nextPage = 101, batchHashes = hashes))
        val pages = FakePages(250, native = (1..250).associateWith { "$LONG_TEXT page $it" })
        val result = extractor(worker, pages).runOnce()
        assertEquals(RunResult.Published(uuid(23), limited = true), result)
        // Only the pages after the published ones are read, and a lease still reads at most a hundred.
        assertEquals((101..200).toList(), pages.nativeCalls)
        assertEquals((10..19).toList(), worker.batches.map { it.ordinal })
        assertEquals(200, worker.batches.last().lastPage)
        assertEquals(250, worker.batches.last().totalPages)
        // The fake Worker refuses a final batch whose manifest does not cover the ten published batches' hashes, so reaching here proves it.
        assertEquals(101, worker.accepted.keys.first())
        assertEquals(200, worker.accepted.keys.last())
    }

    @Test
    fun aReadThatReachesTheEndOfALongerDocumentIsNotLimited() = runBlocking {
        val hashes = (1..10).map { sha256Hex("batch$it".toByteArray()) }
        val worker = FakeWorker(pretendWork(file, startPage = 101, nextBatchOrdinal = 10, nextPage = 101, batchHashes = hashes))
        val pages = FakePages(130, native = (1..130).associateWith { "$LONG_TEXT page $it" })
        assertEquals(RunResult.Published(uuid(23), limited = false), extractor(worker, pages).runOnce())
        assertEquals((101..130).toList(), pages.nativeCalls)
        assertNull(worker.batches.last().totalPages)
    }

    @Test
    fun pagesThatDoNotFitOneBatchAreSplitBetweenBatches() = runBlocking {
        val worker = workerFor()
        val big = "w".repeat(60 * 1024)
        val pages = FakePages(3, native = mapOf(1 to big, 2 to big, 3 to big))
        assertEquals(RunResult.Published(uuid(23), false), extractor(worker, pages).runOnce())
        assertEquals(listOf(0 to 2, 1 to 3), worker.batches.map { it.ordinal to it.lastPage })
        assertEquals(listOf(1, 2, 3), worker.accepted.keys.toList())
    }

    @Test
    fun aPageLargerThanABatchIsCutRatherThanRefused() = runBlocking {
        val worker = workerFor()
        val pages = FakePages(1, native = mapOf(1 to "é".repeat(100 * 1024)))
        assertEquals(RunResult.Published(uuid(23), false), extractor(worker, pages).runOnce())
        val text = worker.accepted.getValue(1).text
        assertTrue(text.toByteArray().size <= 128 * 1024)
        assertTrue(text.isNotEmpty())
    }

    @Test
    fun aNewLeaseContinuesFromTheCheckpointAndKeepsTheServersHashesInTheManifest() = runBlocking {
        val earlier = sha256Hex("earlier".toByteArray())
        val worker = FakeWorker(pretendWork(file, nextBatchOrdinal = 1, nextPage = 11, batchHashes = listOf(earlier)))
        val pages = FakePages(14, native = (1..14).associateWith { "$LONG_TEXT page $it" })
        assertEquals(RunResult.Published(uuid(23), false), extractor(worker, pages).runOnce())
        assertEquals("only the remaining pages are read", (11..14).toList(), pages.nativeCalls)
        assertEquals(1, worker.batches.size)
        assertEquals(1, worker.batches.single().ordinal)
        assertEquals(sha256Hex(earlier.toByteArray()), worker.batches.single().manifestHash)
    }

    @Test
    fun aCheckpointBeyondTheDocumentIsReportedAsFailed() = runBlocking {
        val worker = FakeWorker(pretendWork(file, nextBatchOrdinal = 1, nextPage = 9, batchHashes = listOf("a".repeat(64))))
        val result = extractor(worker, FakePages(5, native = mapOf(1 to LONG_TEXT))).runOnce()
        assertEquals(RunResult.GaveBack(uuid(23), ExtractionOutcome.FAILED), result)
        assertTrue(worker.batches.isEmpty())
    }

    @Test
    fun filesThePhoneCannotReadAreReportedWithTheRightOutcome() = runBlocking {
        for ((open, expected) in listOf(
            OpenOutcome.Encrypted to ExtractionOutcome.ENCRYPTED,
            OpenOutcome.Unsupported to ExtractionOutcome.UNSUPPORTED,
            OpenOutcome.Failed("io") to ExtractionOutcome.FAILED,
        )) {
            val worker = workerFor()
            assertEquals(RunResult.GaveBack(uuid(23), expected), extractor(worker, null, open = open).runOnce())
            assertEquals(listOf(expected), worker.outcomes)
            assertTrue(worker.batches.isEmpty())
            assertTrue(leftovers().isEmpty())
        }
    }

    @Test
    fun aDocumentWithNoPagesIsUnsupported() = runBlocking {
        val worker = workerFor()
        assertEquals(RunResult.GaveBack(uuid(23), ExtractionOutcome.UNSUPPORTED), extractor(worker, FakePages(0)).runOnce())
    }

    @Test
    fun bytesThatAreNotWhatTheLeasePromisedAreNeverOpened() = runBlocking {
        for (tamper in listOf<(FakeWorker) -> Unit>({ it.declaredShaOverride = "f".repeat(64) }, { it.contentLengthOverride = 5 })) {
            val worker = workerFor()
            tamper(worker)
            val pages = FakePages(1, native = mapOf(1 to LONG_TEXT))
            assertEquals(RunResult.GaveBack(uuid(23), ExtractionOutcome.FAILED), extractor(worker, pages).runOnce())
            assertTrue(pages.nativeCalls.isEmpty())
            assertTrue(leftovers().isEmpty())
        }
        // The declared checksum can be right while the bytes are not.
        val worker = workerFor()
        val altered = FakeWorker(pretendWork(file))
        altered.file = PretendFile("%PDF-1.7 different bytes".toByteArray())
        altered.declaredShaOverride = file.sha256
        altered.contentLengthOverride = file.bytes.size.toLong()
        val extractor = DeviceExtractor(altered, altered, opener(OpenOutcome.Opened(FakePages(1))), ExtractionCapabilities(true, true, "android-extract-v1"), { null }, File(dir, "x"), { now }, {})
        assertTrue(extractor.runOnce().let { it is RunResult.GaveBack && it.outcome == ExtractionOutcome.FAILED })
        assertTrue(worker.outcomes.isEmpty())
    }

    @Test
    fun aDownloadThatIsRefusedOrUnavailableIsHandledWithoutOpeningAnything() = runBlocking {
        val cases = listOf(
            OpenResult.Unavailable("network") to RunResult.GaveBack(uuid(23), ExtractionOutcome.UNAVAILABLE),
            OpenResult.Refused("forbidden", 403) to RunResult.GaveBack(uuid(23), ExtractionOutcome.FAILED),
            OpenResult.Gone("not_found", 404) to RunResult.LeaseEnded("not_found"),
            OpenResult.SessionExpired to RunResult.SignIn,
        )
        for ((open, expected) in cases) {
            val worker = workerFor()
            worker.contentResult = open
            assertEquals(expected, extractor(worker, FakePages(1)).runOnce())
        }
    }

    @Test
    fun anUnreadyRecognizerHandsTheFileBackInsteadOfFailingIt() = runBlocking {
        val worker = workerFor()
        val pages = FakePages(3, native = mapOf(1 to LONG_TEXT)).also { it.unavailableOn = 2 }
        assertEquals(RunResult.GaveBack(uuid(23), ExtractionOutcome.UNAVAILABLE), extractor(worker, pages).runOnce())
        assertTrue("nothing partial is published", worker.batches.isEmpty() && !worker.published)
        assertTrue(pages.closed)
    }

    @Test
    fun aPageThatCannotBeReadFailsTheFileVisibly() = runBlocking {
        val worker = workerFor()
        val pages = FakePages(3, native = mapOf(1 to LONG_TEXT, 2 to LONG_TEXT, 3 to LONG_TEXT)).also { it.failNativeOn = 2 }
        assertEquals(RunResult.GaveBack(uuid(23), ExtractionOutcome.FAILED), extractor(worker, pages).runOnce())
        assertFalse(worker.published)
    }

    @Test
    fun aLimitStopsTheRunBetweenPagesAndReturnsTheFile() = runBlocking {
        val worker = workerFor()
        val pages = FakePages(12, native = (1..12).associateWith { "$LONG_TEXT page $it" })
        val gate = RunConditions { if (pages.nativeCalls.size >= 3) "thermal" else null }
        val running = DeviceExtractor(worker, worker, opener(OpenOutcome.Opened(pages)), ExtractionCapabilities(true, true, "android-extract-v1"), gate, File(dir, "x"), { now }, {})
        worker.file = file
        assertEquals(RunResult.Stopped("thermal"), running.runOnce())
        assertEquals(listOf(ExtractionOutcome.UNAVAILABLE), worker.outcomes)
        assertEquals(listOf(1, 2, 3), pages.nativeCalls)
        assertTrue(worker.batches.isEmpty())
    }

    @Test
    fun aLimitThatAlreadyHoldsMeansNoClaimAtAll() = runBlocking {
        stopReason = "battery_low"
        val worker = workerFor()
        assertEquals(RunResult.Stopped("battery_low"), extractor(worker, FakePages(1)).runOnce())
        assertTrue(worker.claimRequests.isEmpty())
    }

    @Test
    fun anUnansweredBatchIsRepeatedWithPausesAndThenGivenUp() = runBlocking {
        val worker = workerFor()
        worker.scriptedSubmits += SubmitResult.Unavailable("network")
        worker.scriptedSubmits += SubmitResult.Unavailable("network")
        val pages = FakePages(1, native = mapOf(1 to LONG_TEXT))
        assertEquals("the third attempt gets through", RunResult.Published(uuid(23), false), extractor(worker, pages).runOnce())
        assertEquals(listOf(2_000L, 4_000L), pauses)
        assertEquals(3, worker.batches.size)
        assertEquals("the same batch each time", 1, worker.batches.toSet().size)

        val failing = workerFor()
        repeat(3) { failing.scriptedSubmits += SubmitResult.Unavailable("network") }
        pauses.clear()
        assertEquals(RunResult.Retry("network"), extractor(failing, FakePages(1, native = mapOf(1 to LONG_TEXT))).runOnce())
        assertTrue("nothing is claimed to have happened", failing.outcomes.isEmpty())
    }

    @Test
    fun whenTheWorkerEndsTheLeaseTheRunStopsWithoutReportingAnything() = runBlocking {
        val worker = workerFor()
        worker.scriptedSubmits += SubmitResult.LeaseEnded("policy_changed")
        assertEquals(RunResult.LeaseEnded("policy_changed"), extractor(worker, FakePages(1, native = mapOf(1 to LONG_TEXT))).runOnce())
        assertTrue(worker.outcomes.isEmpty())
        assertTrue(leftovers().isEmpty())

        val deleted = workerFor()
        deleted.scriptedSubmits += SubmitResult.Deleted
        assertEquals(RunResult.Deleted, extractor(deleted, FakePages(1, native = mapOf(1 to LONG_TEXT))).runOnce())
    }

    @Test
    fun aBatchTheWorkerRefusesForGoodFailsTheFile() = runBlocking {
        val worker = workerFor()
        worker.scriptedSubmits += SubmitResult.Refused("manifest_mismatch", 409)
        assertEquals(RunResult.GaveBack(uuid(23), ExtractionOutcome.FAILED), extractor(worker, FakePages(1, native = mapOf(1 to LONG_TEXT))).runOnce())
    }

    @Test
    fun anAnswerWithAnotherCheckpointIsNotTrusted() = runBlocking {
        val worker = workerFor()
        worker.scriptedSubmits += SubmitResult.Done(BatchResult(uuid(21), 0, BatchStatus.ACCEPTED, "a".repeat(64), 5, 9, published = true, limited = false))
        assertEquals(RunResult.Retry("unexpected_checkpoint"), extractor(worker, FakePages(1, native = mapOf(1 to LONG_TEXT))).runOnce())
    }

    @Test
    fun theLeaseIsRenewedWhileSlowPagesAreRecognized() = runBlocking {
        val worker = workerFor()
        val pages = FakePages(4, recognized = (1..4).associateWith { "Recognized page $it text" }, onRecognize = { now += 2 * 60_000L })
        extractor(worker, pages, ExtractionCapabilities(pdfText = false, ocr = true, extractorVersion = "android-extract-v1")).runOnce()
        assertTrue("a heartbeat went out between slow pages", worker.heartbeats >= 1)
        assertTrue(worker.published)

        val ended = workerFor()
        ended.heartbeatResult = HeartbeatResult.LeaseEnded("lease_lost")
        val slow = FakePages(4, recognized = (1..4).associateWith { "Recognized page $it text" }, onRecognize = { now += 2 * 60_000L })
        assertEquals(RunResult.LeaseEnded("lease_lost"), extractor(ended, slow, ExtractionCapabilities(false, true, "android-extract-v1")).runOnce())
        assertTrue(ended.batches.isEmpty())
    }

    @Test
    fun noWorkAndNoSessionAreReportedAsSuch() = runBlocking {
        val worker = workerFor()
        worker.claims = mutableListOf(NoWork)
        assertEquals(RunResult.Idle(NoWorkReason.NO_WORK), extractor(worker, null, open = OpenOutcome.Unsupported).runOnce())
        worker.claims = mutableListOf(ClaimResult.SessionExpired)
        assertEquals(RunResult.SignIn, extractor(worker, null, open = OpenOutcome.Unsupported).runOnce())
        worker.claims = mutableListOf(ClaimResult.Unavailable("network"))
        assertEquals(RunResult.Retry("network"), extractor(worker, null, open = OpenOutcome.Unsupported).runOnce())
    }

    @Test
    fun cancellingARunHandsTheFileBackAndRemovesTheDownload() = runBlocking {
        val worker = workerFor()
        val reached = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val pages = FakePages(2, recognized = mapOf(1 to "never returned"), onRecognize = {
            reached.complete(Unit)
            never.await()
        })
        val running = async(start = CoroutineStart.UNDISPATCHED) {
            extractor(worker, pages, ExtractionCapabilities(pdfText = false, ocr = true, extractorVersion = "android-extract-v1")).runOnce()
        }
        reached.await()
        assertTrue("the download exists while the run is busy", leftovers().isNotEmpty())
        running.cancelAndJoin()
        assertEquals("the file is handed back at once", listOf(ExtractionOutcome.UNAVAILABLE), worker.outcomes)
        assertTrue(leftovers().isEmpty())
        assertTrue(pages.closed)
    }

    @Test
    fun purgeRemovesEverythingLeftBehind() {
        val worker = workerFor()
        val e = extractor(worker, FakePages(1))
        File(dir, "extraction").mkdirs()
        File(dir, "extraction/leftover.part").writeText("x")
        e.purge()
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun onlyOneRunAtATime() = runBlocking {
        val worker = workerFor()
        val gate = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        val pages = FakePages(1, recognized = mapOf(1 to "text"), onRecognize = {
            reached.complete(Unit)
            gate.await()
        })
        val e = extractor(worker, pages, ExtractionCapabilities(pdfText = false, ocr = true, extractorVersion = "android-extract-v1"))
        val first = async(start = CoroutineStart.UNDISPATCHED) { e.runOnce() }
        reached.await()
        assertEquals(RunResult.Idle(NoWorkReason.BUSY), e.runOnce())
        gate.complete(Unit)
        assertTrue(first.await() is RunResult.Published)
    }
}
