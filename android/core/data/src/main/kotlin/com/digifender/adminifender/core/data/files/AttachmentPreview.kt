package com.digifender.adminifender.core.data.files

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** The result of trying to show a file's content inside the app. */
sealed interface PreviewResult {
    /** Plain text, safe to show as it is. [truncated] says more follows in the file than was shown. */
    data class Text(val text: String, val truncated: Boolean) : PreviewResult

    /** Not text the app will show: it stays a file, and the owner can save a copy. */
    data object NotShown : PreviewResult
}

/**
 * The only in-app view of an attachment: a bounded plain-text view of text-like files. There is no HTML, no markup,
 * no image or document decoding and no link handling, so nothing in a file is ever executed, rendered or opened
 * automatically. Any other format is offered as a saved copy for the owner to open themselves.
 */
object AttachmentPreview {
    const val MAX_BYTES = 256 * 1024
    const val MAX_CHARS = 20_000

    private val TEXT_TYPES = setOf("text/plain", "text/csv", "text/markdown", "application/json")
    private val TEXT_EXTENSIONS = setOf("txt", "csv", "md", "json", "log")

    /** Whether the file's declared type or name says plain text, and it is small enough to look at. HTML, XML and SVG are never text here. */
    fun eligible(declaredType: String?, name: String, sizeBytes: Long): Boolean {
        if (sizeBytes > MAX_BYTES || sizeBytes < 0) return false
        val type = declaredType?.substringBefore(';')?.trim()?.lowercase()
        if (type != null && type in TEXT_TYPES) return true
        if (type != null && type != "application/octet-stream") return false
        return name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS
    }

    /** Decodes strictly as UTF-8 and refuses anything with a NUL or many control characters, which is binary pretending to be text. */
    fun render(bytes: ByteArray): PreviewResult {
        if (bytes.size > MAX_BYTES) return PreviewResult.NotShown
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, start, bytes.size - start)).toString()
        } catch (_: CharacterCodingException) {
            return PreviewResult.NotShown
        }
        val control = text.count { it.code < 0x20 && it != '\n' && it != '\r' && it != '\t' }
        if (text.any { it.code == 0 } || control * 100 > text.length.coerceAtLeast(1)) return PreviewResult.NotShown
        return if (text.length > MAX_CHARS) PreviewResult.Text(text.take(MAX_CHARS), true) else PreviewResult.Text(text, false)
    }
}
