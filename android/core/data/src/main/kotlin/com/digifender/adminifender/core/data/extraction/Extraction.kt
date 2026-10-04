package com.digifender.adminifender.core.data.extraction

import com.digifender.adminifender.core.data.network.CallResult
import com.digifender.adminifender.core.model.Readiness
import com.digifender.adminifender.core.model.mustStop
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable

/** One leased attachment (lld.md#text-extraction-and-ocr). */
@Serializable
data class ExtractionWorkDto(
    val attachmentId: String,
    val sourceId: String,
    val name: String,
    val declaredType: String? = null,
    val sizeBytes: Long,
    val contentHash: String,
    val policyRevision: Long,
    val generationId: String,
    val leaseToken: String,
    val expiresAt: String,
    val nextBatch: Int,
    val nextPage: Int,
    val maxPages: Int,
    val ocrAllowed: Boolean,
    /** Hashes of the batches already stored for this generation, in order. */
    val batchHashes: List<String> = emptyList(),
)

@Serializable
data class ExtractionClaimDto(val work: ExtractionWorkDto? = null, val reason: String? = null)

@Serializable
data class PageDto(val page: Int, val text: String, val method: String)

@Serializable
data class BatchAckDto(val attachmentId: String, val state: String, val batchHash: String, val nextBatch: Int? = null, val nextPage: Int? = null)

data class Batch(val ordinal: Int, val pages: List<PageDto>, val complete: Boolean = false, val paused: Boolean = false, val manifestHash: String? = null)

enum class DownloadResult { OK, WRONG_HASH, TOO_LARGE, UNAVAILABLE, SESSION_EXPIRED, GONE }

interface ExtractionApi {
    suspend fun claimExtraction(idempotencyKey: String, charging: Boolean, batteryPercent: Int, thermal: String): CallResult<ExtractionClaimDto>

    suspend fun sendBatch(work: ExtractionWorkDto, idempotencyKey: String, batch: Batch): CallResult<BatchAckDto>

    suspend fun failExtraction(attachmentId: String, idempotencyKey: String, leaseToken: String, reason: String): CallResult<Unit>

    /** Streams the attachment to [target], refusing more than [maxBytes] and checking the SHA-256. */
    suspend fun download(attachmentId: String, target: File, maxBytes: Long, sha256: String): DownloadResult
}

/** How a page's text was obtained; OCR text is labelled as such (tdd.md#capture-and-extraction). */
enum class PageMethod(val wire: String) { PDF_TEXT("pdf_text"), OCR("ocr") }

sealed interface OpenResult {
    data class Opened(val reader: DocumentReader) : OpenResult

    data object Encrypted : OpenResult

    data object Unreadable : OpenResult
}

/** A PDF or image opened from app-private storage. Android implements it with PdfRenderer and bundled ML Kit. */
interface DocumentReader : AutoCloseable {
    val pageCount: Int

    /** Embedded text of a 1-based page, or null when the platform cannot read embedded text (API 31 to 34, images). */
    suspend fun embeddedText(page: Int): String?

    /** On-device OCR of the rendered page; never sent anywhere else. */
    suspend fun ocr(page: Int): String
}

sealed interface ExtractionOutcome {
    data object NotReady : ExtractionOutcome

    data class NoWork(val reason: String?) : ExtractionOutcome

    data class Published(val state: String) : ExtractionOutcome

    data class Failed(val reason: String) : ExtractionOutcome

    /** The phone stopped (heat, battery, lost lease, no answer); the server resumes from its checkpoint later. */
    data class Stopped(val why: String) : ExtractionOutcome

    data object SignInRequired : ExtractionOutcome
}

/** Embedded text counts as meaningful when it has a few letters or digits; otherwise the page is OCRed. */
fun meaningful(text: String?): Boolean = text != null && text.count { it.isLetterOrDigit() } >= MIN_MEANINGFUL_CHARS

const val MIN_MEANINGFUL_CHARS = 16
const val MAX_PAGES_PER_BATCH = 10
const val MAX_BATCH_TEXT_BYTES = 128 * 1024

/** SHA-256 of the empty page list `[]`, the hash the server gives the closing batch. */
val EMPTY_BATCH_HASH: String = sha256("[]".toByteArray())

fun manifestHash(batchHashes: List<String>): String = sha256(batchHashes.joinToString("\n").toByteArray())

private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * Runs one extraction lease: download and verify, read pages from the server's checkpoint, send batches in order,
 * and finish with an empty closing batch whose manifest covers every acknowledged batch. Embedded PDF text is kept
 * where it is meaningful; other pages are OCRed only when policy allows. Files are deleted when the run ends.
 */
class ExtractionRunner(
    private val api: ExtractionApi,
    private val readiness: () -> Readiness,
    private val open: (File, ExtractionWorkDto) -> OpenResult,
    private val workDir: File,
    private val newKey: () -> String,
) {
    suspend fun runOnce(): ExtractionOutcome {
        val ready = readiness()
        if (!ready.charging || ready.mustStop()) return ExtractionOutcome.NotReady
        val work = when (val c = api.claimExtraction(newKey(), ready.charging, ready.batteryPercent, ready.thermal.wire)) {
            is CallResult.Ok -> c.value.work ?: return ExtractionOutcome.NoWork(c.value.reason)
            CallResult.SessionExpired -> return ExtractionOutcome.SignInRequired
            is CallResult.Refused -> return ExtractionOutcome.Stopped(c.code)
            CallResult.NoAnswer -> return ExtractionOutcome.Stopped("no_answer")
        }
        workDir.mkdirs()
        val file = File(workDir, work.attachmentId)
        try {
            when (api.download(work.attachmentId, file, work.sizeBytes, work.contentHash)) {
                DownloadResult.OK -> Unit
                DownloadResult.WRONG_HASH, DownloadResult.TOO_LARGE -> return fail(work, "unreadable")
                DownloadResult.SESSION_EXPIRED -> return ExtractionOutcome.SignInRequired
                DownloadResult.GONE -> return ExtractionOutcome.Stopped("gone")
                DownloadResult.UNAVAILABLE -> return ExtractionOutcome.Stopped("no_answer")
            }
            val reader = when (val o = open(file, work)) {
                is OpenResult.Opened -> o.reader
                OpenResult.Encrypted -> return fail(work, "encrypted")
                OpenResult.Unreadable -> return fail(work, "unreadable")
            }
            return reader.use { extract(work, it) }
        } finally {
            file.delete()
        }
    }

    private suspend fun extract(work: ExtractionWorkDto, reader: DocumentReader): ExtractionOutcome {
        val last = minOf(reader.pageCount, work.nextPage + work.maxPages - 1)
        val acked = mutableListOf<String>()
        var ordinal = work.nextBatch
        val pending = mutableListOf<PageDto>()
        var pendingBytes = 0

        suspend fun flush(): ExtractionOutcome? {
            if (pending.isEmpty()) return null
            val result = send(work, Batch(ordinal, pending.toList()))
            if (result is CallResult.Ok) {
                acked += result.value.batchHash
                ordinal += 1
                pending.clear()
                pendingBytes = 0
                return null
            }
            return stopFor(result)
        }

        for (page in work.nextPage..last) {
            if (readiness().mustStop()) return ExtractionOutcome.Stopped("device_state")
            val embedded = reader.embeddedText(page)
            val (text, method) = when {
                meaningful(embedded) -> embedded!! to PageMethod.PDF_TEXT
                work.ocrAllowed -> reader.ocr(page) to PageMethod.OCR
                else -> (embedded ?: "") to PageMethod.PDF_TEXT
            }
            val bytes = text.toByteArray().size
            if (bytes > MAX_BATCH_TEXT_BYTES) return fail(work, "limit")
            if (pending.size == MAX_PAGES_PER_BATCH || pendingBytes + bytes > MAX_BATCH_TEXT_BYTES) flush()?.let { return it }
            pending += PageDto(page, text.trim(), method.wire)
            pendingBytes += bytes
        }
        flush()?.let { return it }

        // Earlier runs' batches come back with the claim, so the manifest always covers every stored batch.
        val complete = last >= reader.pageCount
        val closing = Batch(ordinal, emptyList(), complete = complete, paused = !complete, manifestHash = if (complete) manifestHash(work.batchHashes + acked + EMPTY_BATCH_HASH) else null)
        return when (val r = send(work, closing)) {
            is CallResult.Ok -> ExtractionOutcome.Published(r.value.state)
            else -> stopFor(r)
        }
    }

    private suspend fun send(work: ExtractionWorkDto, batch: Batch): CallResult<BatchAckDto> {
        // The same key on a resend: the server's idempotency makes a lost acknowledgement safe to repeat.
        val key = newKey()
        repeat(SEND_ATTEMPTS - 1) {
            val r = api.sendBatch(work, key, batch)
            if (r != CallResult.NoAnswer) return r
        }
        return api.sendBatch(work, key, batch)
    }

    private fun stopFor(result: CallResult<*>): ExtractionOutcome = when (result) {
        CallResult.SessionExpired -> ExtractionOutcome.SignInRequired
        is CallResult.Refused -> ExtractionOutcome.Stopped(result.code)
        else -> ExtractionOutcome.Stopped("no_answer")
    }

    private suspend fun fail(work: ExtractionWorkDto, reason: String): ExtractionOutcome {
        api.failExtraction(work.attachmentId, newKey(), work.leaseToken, reason)
        return ExtractionOutcome.Failed(reason)
    }

    private companion object {
        const val SEND_ATTEMPTS = 3
    }
}
