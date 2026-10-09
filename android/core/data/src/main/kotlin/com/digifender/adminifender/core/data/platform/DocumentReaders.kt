package com.digifender.adminifender.core.data.platform

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.ChecksSdkIntAtLeast
import com.digifender.adminifender.core.data.extraction.DocumentOpener
import com.digifender.adminifender.core.data.extraction.OpenOutcome
import com.digifender.adminifender.core.data.extraction.PageSource
import com.digifender.adminifender.core.data.network.DeviceKind
import java.io.File
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Recognizes the text in a bitmap. Implemented over ML Kit; faked in tests of everything above it. */
interface BitmapRecognizer {
    /** Throws [com.digifender.adminifender.core.data.extraction.RecognitionUnavailable] when the recognizer cannot be used now. */
    suspend fun recognize(bitmap: Bitmap): String?
}

/** The text-layer API arrived with API 35; earlier phones render the page and recognize it (lld.md#text-extraction-and-ocr). */
val PDF_TEXT_LAYER_AVAILABLE: Boolean
    @ChecksSdkIntAtLeast(api = 35) get() = Build.VERSION.SDK_INT >= 35

/** Page rendering for recognition is capped at 4 megapixels at about 200 dpi; an image is decoded at no more than 16 megapixels. */
internal const val MAX_RENDER_PIXELS = 4_000_000L
internal const val MAX_IMAGE_PIXELS = 16_000_000L
private const val RENDER_DPI_SCALE = 200f / 72f

/** Scale from PDF points (1/72 in) to pixels: about 200 dpi, reduced as far as needed to stay within [MAX_RENDER_PIXELS]. */
internal fun renderScale(widthPoints: Int, heightPoints: Int): Float {
    val area = widthPoints.toLong() * heightPoints
    if (area <= 0) return 1f
    // The pixel budget is a memory bound and wins over resolution: an oversized page is recognized at lower resolution.
    return min(RENDER_DPI_SCALE, sqrt(MAX_RENDER_PIXELS.toDouble() / area).toFloat())
}

/** Sample size (a power of two is not required) that brings a decoded image under [MAX_IMAGE_PIXELS]. */
internal fun imageSampleSize(width: Int, height: Int): Int {
    val pixels = width.toLong() * height
    return if (pixels <= MAX_IMAGE_PIXELS) 1 else ceil(sqrt(pixels.toDouble() / MAX_IMAGE_PIXELS)).toInt()
}

/**
 * Opens a downloaded file with the platform: a PDF through [PdfRenderer], an image through [ImageDecoder]. Nothing is
 * shown or shared; pages are rendered into bitmaps that are recycled right after recognition.
 */
class PlatformDocumentOpener(private val context: Context, private val recognizer: BitmapRecognizer) : DocumentOpener {
    override suspend fun open(file: File, kind: DeviceKind): OpenOutcome = withContext(Dispatchers.IO) {
        when (kind) {
            DeviceKind.PDF -> openPdf(file)
            DeviceKind.IMAGE -> openImage(file)
        }
    }

    private fun openPdf(file: File): OpenOutcome {
        val descriptor = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (_: IOException) {
            return OpenOutcome.Failed("open")
        }
        return try {
            OpenOutcome.Opened(PdfPages(PdfRenderer(descriptor), descriptor, recognizer))
        } catch (_: SecurityException) {
            // PdfRenderer reports a password-protected document this way.
            descriptor.close()
            OpenOutcome.Encrypted
        } catch (_: IOException) {
            descriptor.close()
            OpenOutcome.Unsupported
        } catch (_: IllegalArgumentException) {
            descriptor.close()
            OpenOutcome.Unsupported
        }
    }

    private fun openImage(file: File): OpenOutcome = try {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.setTargetSampleSize(imageSampleSize(info.size.width, info.size.height))
            // Recognition reads pixels, which a hardware bitmap does not allow.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        OpenOutcome.Opened(ImagePage(bitmap, recognizer))
    } catch (_: IOException) {
        OpenOutcome.Unsupported
    } catch (_: ImageDecoder.DecodeException) {
        OpenOutcome.Unsupported
    }
}

private class PdfPages(private val renderer: PdfRenderer, private val descriptor: ParcelFileDescriptor, private val recognizer: BitmapRecognizer) : PageSource {
    override val pageCount: Int = renderer.pageCount

    override fun nativeText(page: Int): String? {
        if (!PDF_TEXT_LAYER_AVAILABLE) return null
        return renderer.openPage(page - 1).use { it.textLayer() }
    }

    // The KTX helper would add core-ktx to this module for a single call, so the platform call stays.
    @SuppressLint("UseKtx")
    override suspend fun recognize(page: Int): String? {
        val bitmap = renderer.openPage(page - 1).use { p ->
            val scale = renderScale(p.width, p.height)
            val width = (p.width * scale).toInt().coerceAtLeast(1)
            val height = (p.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                // A PDF page is transparent where nothing is drawn; recognition needs dark text on a light page.
                it.eraseColor(Color.WHITE)
                p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
        try {
            return recognizer.recognize(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() {
        renderer.close()
        descriptor.close()
    }
}

private fun PdfRenderer.Page.textLayer(): String? {
    if (Build.VERSION.SDK_INT < 35) return null
    return getTextContents().joinToString("\n") { it.text }.takeIf { it.isNotBlank() }
}

private class ImagePage(private val bitmap: Bitmap, private val recognizer: BitmapRecognizer) : PageSource {
    override val pageCount: Int = 1

    override fun nativeText(page: Int): String? = null

    override suspend fun recognize(page: Int): String? = recognizer.recognize(bitmap)

    override fun close() = bitmap.recycle()
}
