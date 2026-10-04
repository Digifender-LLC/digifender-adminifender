package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.extraction.Batch
import com.digifender.adminifender.core.data.extraction.BatchAckDto
import com.digifender.adminifender.core.data.extraction.DocumentReader
import com.digifender.adminifender.core.data.extraction.DownloadResult
import com.digifender.adminifender.core.data.extraction.EMPTY_BATCH_HASH
import com.digifender.adminifender.core.data.extraction.ExtractionApi
import com.digifender.adminifender.core.data.extraction.ExtractionClaimDto
import com.digifender.adminifender.core.data.extraction.ExtractionOutcome
import com.digifender.adminifender.core.data.extraction.ExtractionRunner
import com.digifender.adminifender.core.data.extraction.ExtractionWorkDto
import com.digifender.adminifender.core.data.extraction.OpenResult
import com.digifender.adminifender.core.data.extraction.manifestHash
import com.digifender.adminifender.core.data.network.CallResult
import com.digifender.adminifender.core.model.Readiness
import com.digifender.adminifender.core.model.Thermal
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeExtractionApi(var work: ExtractionWorkDto?) : ExtractionApi {
    val batches = mutableListOf<Batch>()
    val keys = mutableListOf<String>()
    val failures = mutableListOf<String>()
    var download = DownloadResult.OK
    var noAnswerOnce = false
    var downloaded: File? = null

    override suspend fun claimExtraction(idempotencyKey: String, charging: Boolean, batteryPercent: Int, thermal: String) = CallResult.Ok(ExtractionClaimDto(work, if (work == null) "no_work" else null))

    override suspend fun sendBatch(work: ExtractionWorkDto, idempotencyKey: String, batch: Batch): CallResult<BatchAckDto> {
        keys += idempotencyKey
        if (noAnswerOnce) {
            noAnswerOnce = false
            return CallResult.NoAnswer
        }
        batches += batch
        val state = when {
            batch.complete -> "ready"
            batch.paused -> "deferred"
            else -> "extracting"
        }
        return CallResult.Ok(BatchAckDto(work.attachmentId, state, if (batch.pages.isEmpty()) EMPTY_BATCH_HASH else "h${batch.ordinal}"))
    }

    override suspend fun failExtraction(attachmentId: String, idempotencyKey: String, leaseToken: String, reason: String): CallResult<Unit> {
        failures += reason
        return CallResult.Ok(Unit)
    }

    override suspend fun download(attachmentId: String, target: File, maxBytes: Long, sha256: String): DownloadResult {
        target.writeText("pdf")
        downloaded = target
        return download
    }
}

private class FakeReader(override val pageCount: Int, private val embedded: (Int) -> String?) : DocumentReader {
    val ocred = mutableListOf<Int>()
    var closed = false

    override suspend fun embeddedText(page: Int) = embedded(page)

    override suspend fun ocr(page: Int): String {
        ocred += page
        return "ocr text for page $page"
    }

    override fun close() {
        closed = true
    }
}

class ExtractionRunnerTest {
    private val dir = Files.createTempDirectory("extract").toFile()
    private var thermal = Thermal.NONE
    private var n = 0

    private fun work(nextPage: Int = 1, nextBatch: Int = 0, maxPages: Int = 100, ocr: Boolean = true, hashes: List<String> = emptyList()) =
        ExtractionWorkDto("att", "src", "scan.pdf", "application/pdf", 10, "c".repeat(64), 2, "gen", "lease", "2026-10-04T00:10:00Z", nextBatch, nextPage, maxPages, ocr, hashes)

    private fun runner(api: ExtractionApi, reader: FakeReader) =
        ExtractionRunner(api, { Readiness(false, true, 80, thermal, null, null) }, { _, _ -> OpenResult.Opened(reader) }, dir, { "k${n++}" })

    @Test
    fun keepsMeaningfulPdfTextAndOcrsTheRestInBatchesOfTen() = runTest {
        val api = FakeExtractionApi(work())
        val reader = FakeReader(12) { page -> if (page % 2 == 0) "Embedded statement text on page $page" else "  " }
        assertEquals(ExtractionOutcome.Published("ready"), runner(api, reader).runOnce())
        assertEquals(listOf(10, 2, 0), api.batches.map { it.pages.size })
        assertEquals(listOf(1, 3, 5, 7, 9, 11), reader.ocred)
        assertEquals("pdf_text", api.batches[0].pages[1].method)
        assertEquals("ocr", api.batches[0].pages[0].method)
        val closing = api.batches.last()
        assertTrue(closing.complete)
        assertEquals(manifestHash(listOf("h0", "h1", EMPTY_BATCH_HASH)), closing.manifestHash)
        assertTrue(reader.closed)
        assertFalse(api.downloaded!!.exists())
    }

    @Test
    fun noAiMailGetsEmbeddedTextOnlyNeverOcr() = runTest {
        val api = FakeExtractionApi(work(ocr = false))
        val reader = FakeReader(2) { null }
        runner(api, reader).runOnce()
        assertTrue(reader.ocred.isEmpty())
        assertEquals(listOf("pdf_text", "pdf_text"), api.batches[0].pages.map { it.method })
    }

    @Test
    fun aLongDocumentPausesAtThePageLimitWithoutAManifest() = runTest {
        val api = FakeExtractionApi(work(maxPages = 3))
        assertEquals(ExtractionOutcome.Published("deferred"), runner(api, FakeReader(5) { "Embedded text that is long enough $it" }).runOnce())
        val closing = api.batches.last()
        assertTrue(closing.paused)
        assertFalse(closing.complete)
        assertNull(closing.manifestHash)
        assertEquals(listOf(1, 2, 3), api.batches[0].pages.map { it.page })
    }

    @Test
    fun aResumedJobStartsAtTheCheckpointAndIncludesEarlierHashes() = runTest {
        val api = FakeExtractionApi(work(nextPage = 4, nextBatch = 2, hashes = listOf("old0", EMPTY_BATCH_HASH)))
        runner(api, FakeReader(5) { "Embedded text that is long enough $it" }).runOnce()
        assertEquals(listOf(4, 5), api.batches[0].pages.map { it.page })
        assertEquals(2, api.batches[0].ordinal)
        assertEquals(manifestHash(listOf("old0", EMPTY_BATCH_HASH, "h2", EMPTY_BATCH_HASH)), api.batches.last().manifestHash)
    }

    @Test
    fun anUnansweredBatchIsResentWithTheSameKey() = runTest {
        val api = FakeExtractionApi(work()).apply { noAnswerOnce = true }
        runner(api, FakeReader(1) { "Embedded text that is long enough" }).runOnce()
        assertEquals(api.keys[0], api.keys[1])
    }

    @Test
    fun heatStopsBetweenPagesAndLeavesTheRestToTheCheckpoint() = runTest {
        val api = FakeExtractionApi(work())
        val reader = FakeReader(3) { page ->
            if (page == 2) thermal = Thermal.SEVERE
            "Embedded text that is long enough $page"
        }
        assertEquals(ExtractionOutcome.Stopped("device_state"), runner(api, reader).runOnce())
        assertTrue(api.batches.isEmpty())
    }

    @Test
    fun aMismatchedDownloadIsReportedAndNothingIsRead() = runTest {
        val api = FakeExtractionApi(work()).apply { download = DownloadResult.WRONG_HASH }
        val reader = FakeReader(1) { "x" }
        assertEquals(ExtractionOutcome.Failed("unreadable"), runner(api, reader).runOnce())
        assertEquals(listOf("unreadable"), api.failures)
        assertFalse(api.downloaded!!.exists())
    }

    @Test
    fun encryptedFilesAreRecordedAsSuch() = runTest {
        val api = FakeExtractionApi(work())
        val r = ExtractionRunner(api, { Readiness(false, true, 80, Thermal.NONE, null, null) }, { _, _ -> OpenResult.Encrypted }, dir, { "k" })
        assertEquals(ExtractionOutcome.Failed("encrypted"), r.runOnce())
    }

    @Test
    fun notChargingClaimsNothing() = runTest {
        val api = FakeExtractionApi(work())
        val r = ExtractionRunner(api, { Readiness(true, false, 80, Thermal.NONE, null, null) }, { _, _ -> OpenResult.Unreadable }, dir, { "k" })
        assertEquals(ExtractionOutcome.NotReady, r.runOnce())
    }
}
