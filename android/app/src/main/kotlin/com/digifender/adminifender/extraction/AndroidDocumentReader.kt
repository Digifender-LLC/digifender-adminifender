package com.digifender.adminifender.extraction

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import com.digifender.adminifender.core.data.extraction.DocumentReader
import com.digifender.adminifender.core.data.extraction.ExtractionWorkDto
import com.digifender.adminifender.core.data.extraction.OpenResult
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Rendering and OCR limits from lld.md#text-extraction-and-ocr. */
private const val MAX_RENDER_PIXELS = 4_000_000.0
private const val MAX_IMAGE_PIXELS = 16_000_000L

/** Opens a leased PDF or image; anything else is reported unreadable so the server records it. */
fun openDocument(file: File, work: ExtractionWorkDto): OpenResult {
    val head = file.inputStream().use { input -> ByteArray(5).also { input.read(it) } }
    return if (String(head, Charsets.ISO_8859_1) == "%PDF-") openPdf(file) else openImage(file)
}

private fun openPdf(file: File): OpenResult {
    val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    return try {
        OpenResult.Opened(PdfReader(fd, PdfRenderer(fd)))
    } catch (_: SecurityException) {
        fd.close()
        OpenResult.Encrypted
    } catch (_: IOException) {
        fd.close()
        OpenResult.Unreadable
    }
}

private fun openImage(file: File): OpenResult {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return OpenResult.Unreadable
    return OpenResult.Opened(ImageReader(file, bounds.outWidth.toLong() * bounds.outHeight))
}

private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

/** Bundled Latin recognition on the phone; no image ever leaves it (FR-010). */
private suspend fun recognize(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
    recognizer.process(InputImage.fromBitmap(bitmap, 0))
        .addOnSuccessListener { cont.resume(it.text) }
        .addOnFailureListener { cont.resumeWithException(it) }
}

private class PdfReader(private val fd: ParcelFileDescriptor, private val renderer: PdfRenderer) : DocumentReader {
    override val pageCount: Int = renderer.pageCount

    override suspend fun embeddedText(page: Int): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
        return withContext(Dispatchers.IO) {
            synchronized(renderer) { renderer.openPage(page - 1).use { p -> p.textContents.joinToString("\n") { it.text } } }
        }
    }

    override suspend fun ocr(page: Int): String {
        val bitmap = withContext(Dispatchers.IO) {
            synchronized(renderer) {
                renderer.openPage(page - 1).use { p ->
                    // One page at a time, at most 4 megapixels, on white so transparent scans stay readable.
                    val scale = minOf(2.0, sqrt(MAX_RENDER_PIXELS / (p.width.toDouble() * p.height)))
                    val bmp = createBitmap(maxOf(1, (p.width * scale).toInt()), maxOf(1, (p.height * scale).toInt()))
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
        return try {
            recognize(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() {
        renderer.close()
        fd.close()
    }
}

private class ImageReader(private val file: File, private val pixels: Long) : DocumentReader {
    override val pageCount = 1

    override suspend fun embeddedText(page: Int): String? = null

    override suspend fun ocr(page: Int): String {
        // Resampled for recognition only; the original bytes stay untouched in the archive.
        var sample = 1
        while (pixels / (sample.toLong() * sample) > MAX_IMAGE_PIXELS) sample *= 2
        val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: throw IOException("unreadable")
        return try {
            recognize(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() = Unit
}
