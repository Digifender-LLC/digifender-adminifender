package com.digifender.adminifender.core.data.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import com.digifender.adminifender.core.model.ExtractedPage
import com.digifender.adminifender.core.model.MAX_EXTRACTION_FILE_BYTES
import com.digifender.adminifender.core.model.MAX_PAGE_TEXT_BYTES
import com.digifender.adminifender.core.model.PageExtractionUnavailable
import com.digifender.adminifender.core.model.PageRecognition
import com.digifender.adminifender.core.model.PdfExtractionOutcome
import com.digifender.adminifender.core.model.PdfPageSource
import com.digifender.adminifender.core.model.extractPdfPages
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.floor
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Implement with the bundled, version-pinned Latin recognizer when its dependency has been verified. */
interface BitmapTextRecognizer {
    val available: Boolean
    suspend fun recognize(bitmap: Bitmap): PageRecognition
}

class UnavailableBitmapTextRecognizer : BitmapTextRecognizer {
    override val available = false
    override suspend fun recognize(bitmap: Bitmap) = PageRecognition.Unavailable("ocr_not_configured")
}

/**
 * Private, hash-checked PDF reads. Native text requires API 35; earlier/scanned pages use only the injected
 * on-device recognizer. This adapter neither downloads documents nor publishes unleased extraction results.
 */
class AndroidPdfExtraction(context: Context, private val recognizer: BitmapTextRecognizer = UnavailableBitmapTextRecognizer()) {
    private val cacheRoot = context.applicationContext.cacheDir.canonicalFile

    suspend fun extract(
        file: File,
        expectedSha256: String,
        startPage: Int = 0,
        allowOcr: Boolean,
        canContinue: suspend () -> String? = { null },
        checkpoint: suspend (ExtractedPage) -> Unit,
    ): PdfExtractionOutcome = withContext(Dispatchers.IO) {
        var nextPage = startPage
        try {
            val canonical = file.canonicalFile
            if (!canonical.toPath().startsWith(cacheRoot.toPath()) || !canonical.isFile) return@withContext PdfExtractionOutcome.Deferred("private_file_required", nextPage)
            if (canonical.length() > MAX_EXTRACTION_FILE_BYTES) return@withContext PdfExtractionOutcome.Deferred("file_limit", nextPage)
            if (!expectedSha256.matches(Regex("^[0-9a-f]{64}$")) || hash(canonical) != expectedSha256) return@withContext PdfExtractionOutcome.Deferred("content_hash_mismatch", nextPage)
            ParcelFileDescriptor.open(canonical, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (startPage !in 0..renderer.pageCount) return@withContext PdfExtractionOutcome.Deferred("invalid_checkpoint", startPage)
                    extractPdfPages(RenderedPages(renderer, recognizer), startPage, allowOcr, canContinue) { page ->
                        checkpoint(page)
                        nextPage = page.page + 1
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            PdfExtractionOutcome.Deferred("encrypted", nextPage)
        } catch (_: IOException) {
            PdfExtractionOutcome.Deferred("parser_error", nextPage)
        } catch (_: IllegalArgumentException) {
            PdfExtractionOutcome.Deferred("parser_error", nextPage)
        }
    }

    private class RenderedPages(private val renderer: PdfRenderer, private val recognizer: BitmapTextRecognizer) : PdfPageSource {
        override val pageCount get() = renderer.pageCount

        override suspend fun text(page: Int): String? = if (Build.VERSION.SDK_INT >= 35) nativeText(page) else null

        @RequiresApi(35)
        private fun nativeText(index: Int): String = renderer.openPage(index).use { page ->
            val text = StringBuilder()
            var utf8Bytes = 0
            for (content in page.textContents) {
                val part = content.text
                if (part.length > MAX_PAGE_TEXT_BYTES) throw PageExtractionUnavailable("text_limit")
                utf8Bytes += part.toByteArray(Charsets.UTF_8).size + if (text.isNotEmpty()) 1 else 0
                if (utf8Bytes > MAX_PAGE_TEXT_BYTES) throw PageExtractionUnavailable("text_limit")
                if (text.isNotEmpty()) text.append('\n')
                text.append(part)
            }
            text.toString()
        }

        override suspend fun recognize(page: Int): PageRecognition {
            if (!recognizer.available) return PageRecognition.Unavailable("ocr_not_configured")
            return renderer.openPage(page).use { opened ->
                val (width, height) = renderSize(opened.width, opened.height)
                val bitmap = createBitmap(width, height)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    opened.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    recognizer.recognize(bitmap)
                } finally { bitmap.recycle() }
            }
        }
    }

    private companion object {
        fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** Render at up to 2x PDF point dimensions, bounded to four megapixels, including extremely thin pages. */
internal fun renderSize(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0)
    val scale = minOf(2.0, sqrt(4_000_000.0 / width.toDouble() / height.toDouble()))
    val scaledWidth = floor(width * scale).toInt().coerceIn(1, 4_000_000)
    val scaledHeight = floor(height * scale).toInt().coerceIn(1, 4_000_000 / scaledWidth)
    return scaledWidth to scaledHeight
}
