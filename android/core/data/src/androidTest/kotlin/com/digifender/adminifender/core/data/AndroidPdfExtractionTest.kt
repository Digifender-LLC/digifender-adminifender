package com.digifender.adminifender.core.data

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.digifender.adminifender.core.data.platform.AndroidPdfExtraction
import com.digifender.adminifender.core.model.ExtractedPage
import com.digifender.adminifender.core.model.ExtractionMethod
import com.digifender.adminifender.core.model.PdfExtractionOutcome
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class AndroidPdfExtractionTest {
    @Test fun extractsNativeTextWithoutOcrAndPreservesOriginalBytes() = runTest {
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("synthetic-pdf-", ".pdf", context.cacheDir)
        try {
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 0).create())
                page.canvas.drawText("Synthetic Invoice 123", 40f, 60f, Paint().apply { textSize = 20f })
                document.finishPage(page)
                file.outputStream().use { document.writeTo(it) }
            } finally { document.close() }
            val original = file.readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }
            val pages = mutableListOf<ExtractedPage>()
            assertEquals(PdfExtractionOutcome.Complete(1), AndroidPdfExtraction(context).extract(file, hash, allowOcr = false, checkpoint = { pages += it }))
            assertEquals(ExtractionMethod.PDF_TEXT, pages.single().method)
            assertTrue(pages.single().text.contains("Synthetic Invoice 123"))
            assertArrayEquals(original, file.readBytes())
            assertEquals(PdfExtractionOutcome.Deferred("content_hash_mismatch", 0), AndroidPdfExtraction(context).extract(file, "0".repeat(64), allowOcr = true, checkpoint = { fail() }))
        } finally { file.delete() }
    }
}
