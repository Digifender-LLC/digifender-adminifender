package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.files.AttachmentPreview
import com.digifender.adminifender.core.data.files.PreviewResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentPreviewTest {
    @Test
    fun onlyPlainTextTypesWithinTheBoundAreEligible() {
        assertTrue(AttachmentPreview.eligible("text/plain; charset=utf-8", "a.bin", 100))
        assertTrue(AttachmentPreview.eligible("application/json", "a", 100))
        assertTrue(AttachmentPreview.eligible(null, "notes.TXT", 100))
        assertTrue(AttachmentPreview.eligible("application/octet-stream", "data.csv", 100))
        // Markup and everything that is parsed or rendered by something is never shown.
        for (type in listOf("text/html", "image/svg+xml", "application/xml", "application/pdf", "image/png", "application/x-sh", "text/x-script")) {
            assertFalse(type, AttachmentPreview.eligible(type, "a.txt", 100))
        }
        assertFalse(AttachmentPreview.eligible(null, "page.html", 100))
        assertFalse(AttachmentPreview.eligible("text/plain", "a.txt", AttachmentPreview.MAX_BYTES + 1L))
        assertTrue(AttachmentPreview.eligible("text/plain", "a.txt", AttachmentPreview.MAX_BYTES.toLong()))
    }

    @Test
    fun textIsShownAsItIsAndLongTextIsCutWithoutPretendingToBeComplete() {
        assertEquals(PreviewResult.Text("héllo\n<b>not markup</b>", false), AttachmentPreview.render("héllo\n<b>not markup</b>".encodeToByteArray()))
        assertEquals(PreviewResult.Text("bom", false), AttachmentPreview.render(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "bom".encodeToByteArray()))
        val long = "x".repeat(AttachmentPreview.MAX_CHARS + 5)
        val shown = AttachmentPreview.render(long.encodeToByteArray()) as PreviewResult.Text
        assertEquals(AttachmentPreview.MAX_CHARS, shown.text.length)
        assertTrue(shown.truncated)
    }

    @Test
    fun binaryAndInvalidTextIsNotShown() {
        assertEquals(PreviewResult.NotShown, AttachmentPreview.render(byteArrayOf(0x25, 0x50, 0x00, 0x01)))
        assertEquals(PreviewResult.NotShown, AttachmentPreview.render(byteArrayOf(0xC3.toByte(), 0x28)))
        assertEquals(PreviewResult.NotShown, AttachmentPreview.render(ByteArray(2000) { if (it % 3 == 0) 1 else 65 }))
        assertEquals(PreviewResult.NotShown, AttachmentPreview.render(ByteArray(AttachmentPreview.MAX_BYTES + 1) { 65 }))
    }
}
