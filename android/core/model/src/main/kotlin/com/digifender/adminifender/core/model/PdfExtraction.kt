package com.digifender.adminifender.core.model

enum class ExtractionMethod { PDF_TEXT, OCR }

/** Page indices are zero based, matching PdfRenderer. The original document remains untouched. */
data class ExtractedPage(val page: Int, val text: String, val method: ExtractionMethod)

sealed interface PageRecognition {
    data class Text(val text: String) : PageRecognition
    data class Unavailable(val reason: String) : PageRecognition
}

interface PdfPageSource {
    val pageCount: Int
    /** Null means native text extraction is unavailable on this platform; blank means a scanned page. */
    suspend fun text(page: Int): String?
    suspend fun recognize(page: Int): PageRecognition
}

/** A bounded adapter refusal, caught before any page checkpoint is acknowledged. */
class PageExtractionUnavailable(val reason: String) : Exception()

sealed interface PdfExtractionOutcome {
    data class Complete(val nextPage: Int) : PdfExtractionOutcome
    data class Deferred(val reason: String, val nextPage: Int) : PdfExtractionOutcome
}

const val MAX_PDF_PAGES_PER_PASS = 100
const val MAX_PAGE_TEXT_BYTES = 128 * 1024
const val MAX_EXTRACTION_FILE_BYTES = 20L * 1024 * 1024

/**
 * One page at a time. The caller durably checkpoints a page before we advance; a stopped or failed callback
 * leaves it eligible for replay. Publication to server search is a separate, fenced protocol.
 */
suspend fun extractPdfPages(
    source: PdfPageSource,
    startPage: Int,
    allowOcr: Boolean,
    canContinue: suspend () -> String? = { null },
    checkpoint: suspend (ExtractedPage) -> Unit,
): PdfExtractionOutcome {
    require(startPage in 0..source.pageCount) { "invalid_checkpoint" }
    val end = minOf(source.pageCount.toLong(), startPage.toLong() + MAX_PDF_PAGES_PER_PASS).toInt()
    for (page in startPage until end) {
        canContinue()?.let { return PdfExtractionOutcome.Deferred(it, page) }
        val native = try { source.text(page) } catch (stopped: PageExtractionUnavailable) { return PdfExtractionOutcome.Deferred(stopped.reason, page) }
        val extracted = if (native != null && native.any { it.isLetterOrDigit() }) {
            ExtractedPage(page, native, ExtractionMethod.PDF_TEXT)
        } else {
            if (!allowOcr) return PdfExtractionOutcome.Deferred("policy_blocked", page)
            when (val result = try { source.recognize(page) } catch (stopped: PageExtractionUnavailable) { return PdfExtractionOutcome.Deferred(stopped.reason, page) }) {
                is PageRecognition.Text -> ExtractedPage(page, result.text, ExtractionMethod.OCR)
                is PageRecognition.Unavailable -> return PdfExtractionOutcome.Deferred(result.reason, page)
            }
        }
        if (extracted.text.toByteArray(Charsets.UTF_8).size > MAX_PAGE_TEXT_BYTES) return PdfExtractionOutcome.Deferred("text_limit", page)
        checkpoint(extracted)
    }
    return if (end < source.pageCount) PdfExtractionOutcome.Deferred("page_limit", end) else PdfExtractionOutcome.Complete(end)
}
