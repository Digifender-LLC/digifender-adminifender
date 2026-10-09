package com.digifender.adminifender.core.data.extraction

import com.digifender.adminifender.core.data.files.blockingWithAbort
import com.digifender.adminifender.core.data.network.AttachmentContentApi
import com.digifender.adminifender.core.data.network.ClaimRequest
import com.digifender.adminifender.core.data.network.ClaimResult
import com.digifender.adminifender.core.data.network.DeviceKind
import com.digifender.adminifender.core.data.network.ExtractionApi
import com.digifender.adminifender.core.data.network.ExtractionBatch
import com.digifender.adminifender.core.data.network.ExtractionOutcome
import com.digifender.adminifender.core.data.network.ExtractionWork
import com.digifender.adminifender.core.data.network.HeartbeatResult
import com.digifender.adminifender.core.data.network.MAX_BATCH_BYTES
import com.digifender.adminifender.core.data.network.MAX_BATCH_PAGES
import com.digifender.adminifender.core.data.network.MAX_EXTRACTION_PAGES
import com.digifender.adminifender.core.data.network.NoWorkReason
import com.digifender.adminifender.core.data.network.OpenResult
import com.digifender.adminifender.core.data.network.OutcomeResult
import com.digifender.adminifender.core.data.network.PageMethod
import com.digifender.adminifender.core.data.network.PageText
import com.digifender.adminifender.core.data.network.SubmitResult
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How one call to [DeviceExtractor.runOnce] ended. Only [Published] changes what the owner can search. */
sealed interface RunResult {
    /** Nothing to do. */
    data class Idle(val reason: NoWorkReason) : RunResult

    data class Published(val attachmentId: String, val limited: Boolean) : RunResult

    /** The phone told the Worker it cannot extract this file (or gave it back); the file's state changed accordingly. */
    data class GaveBack(val attachmentId: String, val outcome: ExtractionOutcome) : RunResult

    /** A limit (battery, heat) stopped the run; the file was handed back to the queue. */
    data class Stopped(val reason: String) : RunResult

    /** The Worker ended the lease (expired, policy changed, email quarantined or changed). Nothing was published. */
    data class LeaseEnded(val code: String) : RunResult

    data object Deleted : RunResult

    /** No usable answer from the Worker; the lease runs out by itself and the file returns to the queue. */
    data class Retry(val code: String) : RunResult

    data object SignIn : RunResult
}

internal const val MEANINGFUL_CHARS = 16
private const val HEARTBEAT_MS = 3 * 60_000L
private const val SUBMIT_ATTEMPTS = 3
private const val RETRY_PAUSE_MS = 2_000L
private const val HAND_BACK_TIMEOUT_MS = 5_000L
private const val COPY_CHUNK = 64 * 1024

/**
 * Leases one deferred PDF or image, reads its pages and submits the text in numbered batches (lld.md#text-extraction-and-ocr).
 * A PDF page's own text layer is used when it has meaningful text; recognition runs only on pages without, and only when the
 * Worker says recognition is allowed for this email. Nothing is searchable until the Worker accepts the final batch.
 */
class DeviceExtractor(
    private val api: ExtractionApi,
    private val content: AttachmentContentApi,
    private val opener: DocumentOpener,
    private val capabilities: ExtractionCapabilities,
    private val conditions: RunConditions,
    private val workDir: File,
    private val clock: () -> Long,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val lock = Mutex()

    /** Removes every downloaded file. Called at start-up and when the session ends: nothing here is worth keeping. */
    fun purge() {
        workDir.listFiles().orEmpty().forEach { it.delete() }
    }

    suspend fun runOnce(): RunResult {
        if (!lock.tryLock()) return RunResult.Idle(NoWorkReason.BUSY)
        try {
            conditions.stopReason()?.let { return RunResult.Stopped(it) }
            if (!capabilities.pdfText && !capabilities.ocr) return RunResult.Idle(NoWorkReason.NO_CAPABILITY)
            return when (val claim = api.claim(ClaimRequest(capabilities.extractorVersion, capabilities.pdfText, capabilities.ocr))) {
                is ClaimResult.NoWork -> RunResult.Idle(claim.reason)
                ClaimResult.SessionExpired -> RunResult.SignIn
                is ClaimResult.Unavailable -> RunResult.Retry(claim.code)
                is ClaimResult.Claimed -> withLease(claim.work)
            }
        } finally {
            lock.unlock()
        }
    }

    private suspend fun withLease(work: ExtractionWork): RunResult {
        workDir.mkdirs()
        val file = File(workDir, "${work.leaseId}.part")
        try {
            return process(work, file)
        } catch (e: CancellationException) {
            // WorkManager stopped the run. Hand the file back at once if the Worker can be reached quickly; the lease expires anyway.
            withContext(NonCancellable) { withTimeoutOrNull(HAND_BACK_TIMEOUT_MS) { api.outcome(work, ExtractionOutcome.UNAVAILABLE) } }
            throw e
        } finally {
            file.delete()
        }
    }

    private suspend fun process(work: ExtractionWork, file: File): RunResult {
        when (val fetched = fetch(work, file)) {
            Fetched.Ok -> Unit
            is Fetched.GiveBack -> return giveBack(work, fetched.outcome)
            is Fetched.End -> return fetched.result
        }
        val source = when (val opened = opener.open(file, work.kind)) {
            is OpenOutcome.Opened -> opened.source
            OpenOutcome.Encrypted -> return giveBack(work, ExtractionOutcome.ENCRYPTED)
            OpenOutcome.Unsupported -> return giveBack(work, ExtractionOutcome.UNSUPPORTED)
            is OpenOutcome.Failed -> return giveBack(work, ExtractionOutcome.FAILED)
        }
        return source.use { read(work, it) }
    }

    private sealed interface Fetched {
        data object Ok : Fetched

        data class GiveBack(val outcome: ExtractionOutcome) : Fetched

        data class End(val result: RunResult) : Fetched
    }

    /** Streams the original into [file], checking size and SHA-256 against what the lease promised. */
    private suspend fun fetch(work: ExtractionWork, file: File): Fetched {
        val stream = when (val opened = content.openAttachment(work.attachmentId)) {
            OpenResult.SessionExpired -> return Fetched.End(RunResult.SignIn)
            // Deleted or no longer this owner's: the lease is moot.
            is OpenResult.Gone -> return Fetched.End(RunResult.LeaseEnded(opened.code))
            is OpenResult.Refused -> return Fetched.GiveBack(ExtractionOutcome.FAILED)
            is OpenResult.Unavailable -> return Fetched.GiveBack(ExtractionOutcome.UNAVAILABLE)
            is OpenResult.Open -> opened.stream
        }
        return stream.use { s ->
            if (s.contentLength >= 0 && s.contentLength != work.sizeBytes) return@use Fetched.GiveBack(ExtractionOutcome.FAILED)
            if (s.declaredSha256 != null && s.declaredSha256 != work.contentHash) return@use Fetched.GiveBack(ExtractionOutcome.FAILED)
            blockingWithAbort(s::abort) {
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(COPY_CHUNK)
                var count = 0L
                try {
                    FileOutputStream(file).use { out ->
                        while (true) {
                            ensureActive()
                            val n = s.body.read(buffer)
                            if (n < 0) break
                            count += n
                            if (count > work.sizeBytes) return@blockingWithAbort Fetched.GiveBack(ExtractionOutcome.FAILED)
                            digest.update(buffer, 0, n)
                            out.write(buffer, 0, n)
                        }
                    }
                } catch (_: IOException) {
                    // A dropped connection or a full disk says nothing about the file: it goes back to the queue.
                    return@blockingWithAbort Fetched.GiveBack(ExtractionOutcome.UNAVAILABLE)
                }
                if (count != work.sizeBytes || digest.digest().toHex() != work.contentHash) Fetched.GiveBack(ExtractionOutcome.FAILED) else Fetched.Ok
            }
        }
    }

    private suspend fun giveBack(work: ExtractionWork, outcome: ExtractionOutcome): RunResult = when (val result = api.outcome(work, outcome)) {
        OutcomeResult.Recorded -> RunResult.GaveBack(work.attachmentId, outcome)
        is OutcomeResult.LeaseEnded -> RunResult.LeaseEnded(result.code)
        OutcomeResult.SessionExpired -> RunResult.SignIn
        is OutcomeResult.Unavailable -> RunResult.Retry(result.code)
    }

    private suspend fun stop(work: ExtractionWork, reason: String): RunResult {
        giveBack(work, ExtractionOutcome.UNAVAILABLE)
        return RunResult.Stopped(reason)
    }

    private suspend fun read(work: ExtractionWork, source: PageSource): RunResult {
        if (source.pageCount < 1) return giveBack(work, ExtractionOutcome.UNSUPPORTED)
        val last = minOf(source.pageCount, MAX_EXTRACTION_PAGES)
        // A checkpoint past the end cannot be continued: the Worker needs the final batch to carry a real page.
        if (work.nextPage > last) return giveBack(work, ExtractionOutcome.FAILED)

        val hashes = work.batchHashes.toMutableList()
        var ordinal = work.nextBatchOrdinal
        var firstPage = work.nextPage
        val pending = mutableListOf<PageText>()
        var pendingBytes = 0
        var lastBeat = clock()

        /** Sends the pending pages as the batch ending at [lastPage]. Null means keep going; otherwise the run is over. */
        suspend fun flush(lastPage: Int, complete: Boolean): RunResult? {
            val batch = ExtractionBatch(
                ordinal = ordinal,
                pages = pending.toList(),
                lastPage = lastPage,
                complete = complete,
                totalPages = if (complete && source.pageCount > lastPage) source.pageCount else null,
                manifestHash = if (complete) sha256Hex(hashes.joinToString("\n").toByteArray()) else null,
            )
            when (val sent = submitWithRetry(work, batch)) {
                is SubmitResult.Done -> {
                    val r = sent.result
                    if (r.nextBatchOrdinal != ordinal + 1 || r.nextPage != lastPage + 1) return RunResult.Retry("unexpected_checkpoint")
                    hashes += r.batchHash
                    if (r.published) return RunResult.Published(work.attachmentId, r.limited)
                    if (complete) return RunResult.Retry("not_published")
                }
                is SubmitResult.LeaseEnded -> return RunResult.LeaseEnded(sent.code)
                SubmitResult.Deleted -> return RunResult.Deleted
                // A batch the Worker refuses for good is a mistake on one side; failing the file is visible, retrying it forever is not.
                is SubmitResult.Refused -> return giveBack(work, ExtractionOutcome.FAILED)
                SubmitResult.SessionExpired -> return RunResult.SignIn
                is SubmitResult.Unavailable -> return RunResult.Retry(sent.code)
            }
            pending.clear()
            pendingBytes = 0
            ordinal += 1
            firstPage = lastPage + 1
            lastBeat = clock()
            return null
        }

        var page = work.nextPage
        while (page <= last) {
            conditions.stopReason()?.let { return stop(work, it) }
            if (clock() - lastBeat >= HEARTBEAT_MS) {
                when (val beat = api.heartbeat(work)) {
                    is HeartbeatResult.Renewed -> lastBeat = clock()
                    is HeartbeatResult.LeaseEnded -> return RunResult.LeaseEnded(beat.code)
                    HeartbeatResult.SessionExpired -> return RunResult.SignIn
                    // The next accepted batch renews the lease too.
                    is HeartbeatResult.Unavailable -> Unit
                }
            }
            val text = try {
                readPage(work, source, page)
            } catch (_: RecognitionUnavailable) {
                return giveBack(work, ExtractionOutcome.UNAVAILABLE)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return giveBack(work, ExtractionOutcome.FAILED)
            }
            if (text != null) {
                val bytes = text.text.toByteArray().size
                // A page never shares a batch with more than the batch can hold; the pages before it go first.
                if (pending.isNotEmpty() && pendingBytes + bytes > MAX_BATCH_BYTES) flush(page - 1, complete = false)?.let { return it }
                pending += text
                pendingBytes += bytes
            }
            when {
                page == last -> flush(page, complete = true)?.let { return it }
                page - firstPage + 1 >= MAX_BATCH_PAGES -> flush(page, complete = false)?.let { return it }
            }
            page += 1
        }
        // Unreachable: the page that is `last` completes the run above.
        return RunResult.Retry("incomplete")
    }

    private suspend fun submitWithRetry(work: ExtractionWork, batch: ExtractionBatch): SubmitResult {
        var result: SubmitResult = SubmitResult.Unavailable("none")
        for (attempt in 0 until SUBMIT_ATTEMPTS) {
            // Repeating a batch is safe: the Worker answers a stored one as a replay with the same hash.
            result = api.submit(work, batch)
            if (result !is SubmitResult.Unavailable) return result
            if (attempt < SUBMIT_ATTEMPTS - 1) pause(RETRY_PAUSE_MS shl attempt)
        }
        return result
    }

    /**
     * The page's text, or null when it has none the owner could search. The text layer wins when it has meaningful text;
     * recognition runs only for pages without, and only when allowed and available.
     */
    private suspend fun readPage(work: ExtractionWork, source: PageSource, page: Int): PageText? {
        val canRecognize = work.ocrAllowed && capabilities.ocr
        var layer: String? = null
        if (work.kind == DeviceKind.PDF && capabilities.pdfText) {
            layer = withContext(Dispatchers.Default) { source.nativeText(page) }?.clean()?.takeIf { it.isNotEmpty() }
            // Without recognition a short text layer is all there is, so it is kept.
            if (layer != null && (layer.meaningful() || !canRecognize)) return PageText(page, 0, PageMethod.PDF_TEXT, layer.fitBatch())
        }
        if (!canRecognize) return null
        val recognized = source.recognize(page)?.clean()?.takeIf { it.isNotEmpty() }
        if (recognized != null) return PageText(page, 0, PageMethod.OCR, recognized.fitBatch())
        // Recognition found nothing: the thin text layer, if any, is still better than none.
        return layer?.let { PageText(page, 0, PageMethod.PDF_TEXT, it.fitBatch()) }
    }
}

private fun String.clean() = replace("\u0000", "").trim()

private fun String.meaningful() = count { it.isLetterOrDigit() } >= MEANINGFUL_CHARS

/** A page's text cannot be larger than one batch; the rest of an absurdly long page is not sent. */
private fun String.fitBatch(): String {
    var fitted = this
    while (fitted.toByteArray().size > MAX_BATCH_BYTES) fitted = fitted.take(fitted.length * 3 / 4)
    // Never end on half of a surrogate pair.
    if (fitted.isNotEmpty() && Character.isHighSurrogate(fitted.last())) fitted = fitted.dropLast(1)
    return fitted
}

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
