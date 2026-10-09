package com.digifender.adminifender.core.data.extraction

import com.digifender.adminifender.core.data.network.DeviceKind
import java.io.Closeable
import java.io.File

/** The recognizer or its model cannot be used right now; the file goes back to the queue instead of failing. */
class RecognitionUnavailable(message: String) : Exception(message)

/**
 * One opened document, page numbers from 1. The adapters are the only code that touches PdfRenderer, bitmaps or ML Kit,
 * so everything above them is tested on the JVM with fakes.
 */
interface PageSource : Closeable {
    val pageCount: Int

    /** The page's native text layer, or null when it has none or the platform cannot read one. Blocking. */
    fun nativeText(page: Int): String?

    /** Recognized text of the page, or null when recognition found none. Throws [RecognitionUnavailable] when the recognizer is not ready. */
    suspend fun recognize(page: Int): String?
}

sealed interface OpenOutcome {
    data class Opened(val source: PageSource) : OpenOutcome

    /** A password-protected PDF. It stays retained and downloadable, with the detail `encrypted`. */
    data object Encrypted : OpenOutcome

    /** Not a document this phone can read (damaged, or an image format the platform cannot decode). */
    data object Unsupported : OpenOutcome

    data class Failed(val code: String) : OpenOutcome
}

fun interface DocumentOpener {
    suspend fun open(file: File, kind: DeviceKind): OpenOutcome
}

/** What this phone can do. [pdfText] is the text layer of a PDF (API 35 and later); [ocr] is the bundled Latin recognizer. */
data class ExtractionCapabilities(val pdfText: Boolean, val ocr: Boolean, val extractorVersion: String)

/** Battery, thermal and similar limits, asked before every page. A non-null reason stops the run and hands the file back. */
fun interface RunConditions {
    fun stopReason(): String?
}
