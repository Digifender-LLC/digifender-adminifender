package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PdfExtractionTest {
    private class Document(private val text: List<String?>, private val ocrReady: Boolean = false) : PdfPageSource {
        override val pageCount = text.size
        var ocrCalls = 0
        override suspend fun text(page: Int) = text[page]
        override suspend fun recognize(page: Int): PageRecognition {
            ocrCalls++
            return if (ocrReady) PageRecognition.Text("Scanned invoice 123") else PageRecognition.Unavailable("ocr_unavailable")
        }
    }

    @Test
    fun preservesPageReferencesAndCheckpointsNativeAndScannedPages() = runTest {
        val document = Document(listOf("Invoice 123", ""), ocrReady = true)
        val pages = mutableListOf<ExtractedPage>()
        val result = extractPdfPages(document, 0, allowOcr = true, checkpoint = { pages += it })
        assertEquals(PdfExtractionOutcome.Complete(2), result)
        assertEquals(listOf(0, 1), pages.map { it.page })
        assertEquals(listOf(ExtractionMethod.PDF_TEXT, ExtractionMethod.OCR), pages.map { it.method })
        assertEquals(1, document.ocrCalls)
    }

    @Test
    fun missingOcrIsDeferredAtTheUnprocessedPageAndNeverInventsText() = runTest {
        val document = Document(listOf("Native text", ""))
        val pages = mutableListOf<ExtractedPage>()
        assertEquals(PdfExtractionOutcome.Deferred("ocr_unavailable", 1), extractPdfPages(document, 0, true, checkpoint = { pages += it }))
        assertEquals(listOf("Native text"), pages.map { it.text })
    }

    @Test
    fun noAiStillExtractsNativeTextButNeverCallsOcr() = runTest {
        val document = Document(listOf("Native text", null), ocrReady = true)
        assertEquals(PdfExtractionOutcome.Deferred("policy_blocked", 1), extractPdfPages(document, 0, false, checkpoint = {}))
        assertEquals(0, document.ocrCalls)
    }

    @Test
    fun pageLimitAndResumeDoNotReprocessAcknowledgedPages() = runTest {
        val document = Document(List(102) { "Page $it" })
        val pages = mutableListOf<ExtractedPage>()
        assertEquals(PdfExtractionOutcome.Deferred("page_limit", 100), extractPdfPages(document, 0, true, checkpoint = { pages += it }))
        assertEquals(PdfExtractionOutcome.Complete(102), extractPdfPages(document, 100, true, checkpoint = { pages += it }))
        assertEquals((0 until 102).toList(), pages.map { it.page })
    }

    @Test
    fun cancellationGateAndOversizedTextKeepTheCurrentCheckpoint() = runTest {
        val document = Document(listOf("Text"))
        assertEquals(PdfExtractionOutcome.Deferred("thermal", 0), extractPdfPages(document, 0, true, canContinue = { "thermal" }, checkpoint = { fail() }))
        val huge = Document(listOf("é".repeat(70_000)))
        assertEquals(PdfExtractionOutcome.Deferred("text_limit", 0), extractPdfPages(huge, 0, true, checkpoint = { fail() }))
    }
}
