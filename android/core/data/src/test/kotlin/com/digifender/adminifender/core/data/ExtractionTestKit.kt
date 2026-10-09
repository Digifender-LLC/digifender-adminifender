package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.extraction.DocumentOpener
import com.digifender.adminifender.core.data.extraction.OpenOutcome
import com.digifender.adminifender.core.data.extraction.PageSource
import com.digifender.adminifender.core.data.extraction.RecognitionUnavailable
import com.digifender.adminifender.core.data.extraction.sha256Hex
import com.digifender.adminifender.core.data.network.AttachmentContentApi
import com.digifender.adminifender.core.data.network.BatchResult
import com.digifender.adminifender.core.data.network.BatchStatus
import com.digifender.adminifender.core.data.network.ClaimRequest
import com.digifender.adminifender.core.data.network.ClaimResult
import com.digifender.adminifender.core.data.network.ContentStream
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
import com.digifender.adminifender.core.data.network.batchRequestBody
import com.digifender.adminifender.core.data.network.outcomeRequestBody
import com.digifender.adminifender.core.data.network.parseBatchRequest
import com.digifender.adminifender.core.data.network.parseOutcomeRequest
import java.io.ByteArrayInputStream
import java.io.File

/** The bytes of a pretend attachment and what the lease promises about them. */
class PretendFile(val bytes: ByteArray = "%PDF-1.7 pretend".toByteArray()) {
    val sha256: String = sha256Hex(bytes)
}

fun pretendWork(
    file: PretendFile,
    kind: DeviceKind = DeviceKind.PDF,
    ocrAllowed: Boolean = true,
    nextBatchOrdinal: Int = 0,
    nextPage: Int = 1,
    batchHashes: List<String> = emptyList(),
) = ExtractionWork(
    leaseId = uuid(21),
    leaseToken = uuid(22),
    attachmentId = uuid(23),
    sourceId = uuid(24),
    kind = kind,
    contentHash = file.sha256,
    sizeBytes = file.bytes.size.toLong(),
    extractorVersion = "android-extract-v1",
    policyRevision = 3,
    generationId = "dev:${uuid(23)}:${file.sha256.take(16)}:android-extract-v1:p3",
    ocrAllowed = ocrAllowed,
    nextBatchOrdinal = nextBatchOrdinal,
    nextPage = nextPage,
    batchHashes = batchHashes,
    expiresAtMs = 0,
)

/**
 * The Worker's batch rules, as the phone must satisfy them, with every request first read back by the strict contract
 * parser. A rule broken here is a bug in the phone, reported as a 409 or 400 exactly as the real Worker would.
 */
class FakeWorker(private val work: ExtractionWork) : ExtractionApi, AttachmentContentApi {
    var file: PretendFile = PretendFile()
    var claims: MutableList<ClaimResult> = mutableListOf(ClaimResult.Claimed(work))
    val claimRequests = mutableListOf<ClaimRequest>()
    val batches = mutableListOf<ExtractionBatch>()
    val outcomes = mutableListOf<ExtractionOutcome>()
    var heartbeats = 0

    /** Scripted answers for the next submits, consumed first; an empty queue means "apply the rules". */
    val scriptedSubmits = ArrayDeque<SubmitResult>()
    var contentResult: OpenResult? = null
    var contentLengthOverride: Long? = null
    var declaredShaOverride: String? = null
    var heartbeatResult: HeartbeatResult = HeartbeatResult.Renewed(0)
    var onSubmit: () -> Unit = {}

    /** What the Worker would hold if the run finished: the pages it accepted, by page number. */
    val accepted = sortedMapOf<Int, PageText>()
    var published = false
    var publishedLimited = false
    private val hashes = work.batchHashes.toMutableList()
    private var nextOrdinal = work.nextBatchOrdinal
    private var nextPage = work.nextPage

    override suspend fun claim(request: ClaimRequest): ClaimResult {
        claimRequests += request
        return if (claims.size > 1) claims.removeAt(0) else claims.first()
    }

    override suspend fun heartbeat(work: ExtractionWork): HeartbeatResult {
        heartbeats += 1
        return heartbeatResult
    }

    override suspend fun submit(work: ExtractionWork, batch: ExtractionBatch): SubmitResult {
        onSubmit()
        // The request must satisfy the shared schema exactly as the Worker's validator would read it.
        val sent = parseBatchRequest(batchRequestBody(work, batch))
        check(sent.batch == batch) { "the request does not round-trip" }
        batches += batch
        scriptedSubmits.removeFirstOrNull()?.let { return it }
        fun refuse(code: String) = SubmitResult.Refused(code, 409)
        if (batch.ordinal != nextOrdinal) return refuse("batch_out_of_order")
        if (batch.lastPage < nextPage || batch.lastPage - nextPage + 1 > MAX_BATCH_PAGES) return SubmitResult.Refused("invalid_request", 400)
        if (!batch.complete && batch.lastPage >= MAX_EXTRACTION_PAGES) return SubmitResult.Refused("invalid_request", 400)
        if (batch.pages.sumOf { it.text.toByteArray().size } > MAX_BATCH_BYTES) return SubmitResult.Refused("invalid_request", 400)
        var previous = nextPage - 1
        for (page in batch.pages) {
            if (page.page <= previous || page.page > batch.lastPage || page.part != 0) return SubmitResult.Refused("invalid_request", 400)
            if (page.method == PageMethod.OCR && !work.ocrAllowed) return refuse("ocr_not_allowed")
            previous = page.page
        }
        if (batch.complete) {
            // The manifest covers the batches before this one, as the server reported their hashes.
            if (batch.manifestHash != sha256Hex(hashes.joinToString("\n").toByteArray())) return refuse("manifest_mismatch")
            if (batch.totalPages != null && batch.totalPages < batch.lastPage) return SubmitResult.Refused("invalid_request", 400)
        }
        val hash = sha256Hex("${batch.ordinal}:${batch.pages.joinToString { "${it.page}/${it.method}/${it.text}" }}:${batch.lastPage}".toByteArray())
        hashes += hash
        batch.pages.forEach { accepted[it.page] = it }
        nextOrdinal += 1
        nextPage = batch.lastPage + 1
        if (batch.complete) {
            published = true
            publishedLimited = batch.totalPages != null && batch.totalPages > batch.lastPage
        }
        return SubmitResult.Done(BatchResult(work.leaseId, batch.ordinal, BatchStatus.ACCEPTED, hash, nextOrdinal, nextPage, batch.complete, batch.complete && publishedLimited))
    }

    override suspend fun outcome(work: ExtractionWork, outcome: ExtractionOutcome): OutcomeResult {
        check(parseOutcomeRequest(outcomeRequestBody(work, outcome)).outcome == outcome)
        outcomes += outcome
        return OutcomeResult.Recorded
    }

    var openCount = 0

    override suspend fun openAttachment(attachmentId: String): OpenResult {
        openCount += 1
        contentResult?.let { return it }
        return OpenResult.Open(ContentStream(contentLengthOverride ?: file.bytes.size.toLong(), declaredShaOverride ?: file.sha256, ByteArrayInputStream(file.bytes)) {})
    }
}

/** A document whose pages are scripted: a page's native text and its recognized text, by page number. */
class FakePages(
    override val pageCount: Int,
    private val native: Map<Int, String?> = emptyMap(),
    private val recognized: Map<Int, String?> = emptyMap(),
    private val onRecognize: suspend (Int) -> Unit = {},
) : PageSource {
    val nativeCalls = mutableListOf<Int>()
    val recognizeCalls = mutableListOf<Int>()
    var closed = false
    var failNativeOn: Int? = null
    var unavailableOn: Int? = null

    override fun nativeText(page: Int): String? {
        nativeCalls += page
        if (failNativeOn == page) error("broken page")
        return native[page]
    }

    override suspend fun recognize(page: Int): String? {
        recognizeCalls += page
        if (unavailableOn == page) throw RecognitionUnavailable("model not ready")
        onRecognize(page)
        return recognized[page]
    }

    override fun close() {
        closed = true
    }
}

fun opener(outcome: OpenOutcome) = DocumentOpener { _: File, _: DeviceKind -> outcome }

val NoWork = ClaimResult.NoWork(NoWorkReason.NO_WORK)
