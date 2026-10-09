package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.AccessTokens
import com.digifender.adminifender.core.data.network.BatchStatus
import com.digifender.adminifender.core.data.network.ClaimRequest
import com.digifender.adminifender.core.data.network.ClaimResponse
import com.digifender.adminifender.core.data.network.ClaimResult
import com.digifender.adminifender.core.data.network.ExtractionBatch
import com.digifender.adminifender.core.data.network.ExtractionOutcome
import com.digifender.adminifender.core.data.network.ExtractionWork
import com.digifender.adminifender.core.data.network.HeartbeatResult
import com.digifender.adminifender.core.data.network.HttpExtractionApi
import com.digifender.adminifender.core.data.network.NoWorkReason
import com.digifender.adminifender.core.data.network.OutcomeResult
import com.digifender.adminifender.core.data.network.PageMethod
import com.digifender.adminifender.core.data.network.PageText
import com.digifender.adminifender.core.data.network.ReadMoreOutcome
import com.digifender.adminifender.core.data.network.SubmitResult
import com.digifender.adminifender.core.data.network.isUuid
import com.digifender.adminifender.core.data.network.parseBatchRequest
import com.digifender.adminifender.core.data.network.parseClaim
import com.digifender.adminifender.core.data.network.parseClaimRequest
import com.digifender.adminifender.core.data.network.parseOutcomeRequest
import com.digifender.adminifender.core.data.network.wireJson
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpExtractionApiTest {
    private val server = MockWebServer()
    private var signedIn = true
    private val refused = mutableListOf<String>()
    private lateinit var api: HttpExtractionApi
    private lateinit var work: ExtractionWork

    @Before
    fun start() {
        server.start()
        val tokens = object : AccessTokens {
            override suspend fun current() = if (signedIn) "access-token" else null
            override suspend fun rejected(token: String) {
                refused += token
            }
        }
        api = HttpExtractionApi(OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build(), server.url("/"), tokens)
        work = (parseClaim(wireJson.parseToJsonElement(contractFile("fixtures/v1/extraction-claim/valid/work-fresh.json"))) as ClaimResponse.Work).work
    }

    @After
    fun stop() = server.close()

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code).addHeader("content-type", "application/json").body(body).build()

    private fun data(contract: String, name: String) = """{"data":${contractFile("fixtures/v1/$contract/valid/$name.json").trim()}}"""

    private fun error(code: String) = """{"error":{"code":"$code","message":"m","retryable":false,"details":null}}"""

    private val batch = ExtractionBatch(0, listOf(PageText(1, 0, PageMethod.PDF_TEXT, "Invoice 7731 is due on Friday.")), 1, false)

    @Test
    fun claimingSendsTheCapabilitiesWithAFreshKeyAndReadsTheLeasedFile() = runBlocking {
        server.enqueue(json(200, data("extraction-claim", "work-fresh")))
        val claimed = api.claim(ClaimRequest("android-extract-v1", pdfText = true, ocr = false)) as ClaimResult.Claimed
        assertEquals(work, claimed.work)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/device-work/extraction/claim", request.url.encodedPath)
        assertEquals("Bearer access-token", request.headers["authorization"])
        assertTrue(isUuid(request.headers["idempotency-key"]!!))
        assertEquals(ClaimRequest("android-extract-v1", pdfText = true, ocr = false), parseClaimRequest(wireJson.parseToJsonElement(request.body!!.utf8())))
    }

    @Test
    fun claimingTellsWhyThereIsNoWorkAndSendsNothingWithoutASession() = runBlocking {
        server.enqueue(json(200, data("extraction-claim", "busy")))
        assertEquals(ClaimResult.NoWork(NoWorkReason.BUSY), api.claim(ClaimRequest("android-extract-v1", true, true)))
        signedIn = false
        assertEquals(ClaimResult.SessionExpired, api.claim(ClaimRequest("android-extract-v1", true, true)))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aRefusedTokenIsReportedAndAnAnswerThatIsNotTheContractIsUnavailable() = runBlocking {
        server.enqueue(json(401, error("unauthenticated")))
        assertEquals(ClaimResult.SessionExpired, api.claim(ClaimRequest("android-extract-v1", true, true)))
        assertEquals(listOf("access-token"), refused)
        server.enqueue(json(200, """{"data":{"work":{"leaseId":"nope"}}}"""))
        assertEquals(ClaimResult.Unavailable("malformed_response"), api.claim(ClaimRequest("android-extract-v1", true, true)))
        server.enqueue(json(503, error("unavailable")))
        assertEquals(ClaimResult.Unavailable("unavailable"), api.claim(ClaimRequest("android-extract-v1", true, true)))
    }

    @Test
    fun aBatchIsSentUnderTheLeaseAndItsAnswerIsChecked() = runBlocking {
        server.enqueue(json(200, """{"data":{"leaseId":"${work.leaseId}","batchOrdinal":0,"status":"accepted","batchHash":"${"c".repeat(64)}","nextBatchOrdinal":1,"nextPage":2,"published":false}}"""))
        val done = api.submit(work, batch) as SubmitResult.Done
        assertEquals(BatchStatus.ACCEPTED, done.result.status)
        assertEquals("c".repeat(64), done.result.batchHash)
        val request = server.takeRequest()
        assertEquals("/api/v1/device-work/extraction/${work.leaseId}/batch", request.url.encodedPath)
        val sent = parseBatchRequest(wireJson.parseToJsonElement(request.body!!.utf8()))
        assertEquals(work.leaseToken, sent.leaseToken)
        assertEquals(work.generationId, sent.generationId)
        assertEquals(batch, sent.batch)
    }

    @Test
    fun anAnswerForAnotherBatchIsNotAnAnswerToThisOne() = runBlocking {
        server.enqueue(json(200, """{"data":{"leaseId":"${work.leaseId}","batchOrdinal":3,"status":"accepted","batchHash":"${"c".repeat(64)}","nextBatchOrdinal":4,"nextPage":5,"published":false}}"""))
        assertEquals(SubmitResult.Unavailable("malformed_response"), api.submit(work, batch))
    }

    @Test
    fun batchRefusalsAreToldApart() = runBlocking {
        suspend fun submitting(status: Int, code: String): SubmitResult {
            server.enqueue(json(status, error(code)))
            return api.submit(work, batch)
        }
        for (code in listOf("lease_lost", "not_eligible", "policy_changed", "input_changed", "generation_mismatch", "generation_corrupt")) {
            assertEquals(code, SubmitResult.LeaseEnded(code), submitting(409, code))
        }
        assertEquals(SubmitResult.Deleted, submitting(410, "deleted"))
        assertEquals(SubmitResult.Refused("batch_conflict", 409), submitting(409, "batch_conflict"))
        assertEquals(SubmitResult.Refused("manifest_mismatch", 409), submitting(409, "manifest_mismatch"))
        assertEquals(SubmitResult.Refused("ocr_not_allowed", 409), submitting(409, "ocr_not_allowed"))
        assertEquals(SubmitResult.Refused("invalid_request", 400), submitting(400, "invalid_request"))
        // Busy or broken Workers say nothing about the batch: it may be sent again.
        assertEquals(SubmitResult.Unavailable("archive_unavailable"), submitting(503, "archive_unavailable"))
        assertEquals(SubmitResult.Unavailable("rate_limited"), submitting(429, "rate_limited"))
    }

    @Test
    fun heartbeatsRenewAndLeaseEndsAreTold() = runBlocking {
        server.enqueue(json(200, """{"data":{"leaseId":"${work.leaseId}","expiresAt":"2026-10-09T12:20:00.000Z"}}"""))
        assertEquals(HeartbeatResult.Renewed(java.time.Instant.parse("2026-10-09T12:20:00.000Z").toEpochMilli()), api.heartbeat(work))
        assertEquals("/api/v1/device-work/extraction/${work.leaseId}/heartbeat", server.takeRequest().url.encodedPath)
        server.enqueue(json(409, error("lease_lost")))
        assertEquals(HeartbeatResult.LeaseEnded("lease_lost"), api.heartbeat(work))
        server.enqueue(json(200, """{"data":{"leaseId":"${uuid(9)}","expiresAt":"2026-10-09T12:20:00.000Z"}}"""))
        assertEquals(HeartbeatResult.Unavailable("malformed_response"), api.heartbeat(work))
    }

    @Test
    fun askingToReadMoreSendsAnEmptyRequestAndReadsWhereTheFileWillContinue() = runBlocking {
        server.enqueue(json(200, "{\"data\":{\"attachmentId\":\"${work.attachmentId}\",\"state\":\"deferred\",\"detail\":\"device_unavailable\",\"resumePage\":101}}"))
        assertEquals(ReadMoreOutcome.Requested(101), api.readMore(work.attachmentId))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/attachments/${work.attachmentId}/read-more", request.url.encodedPath)
        assertTrue(isUuid(request.headers["idempotency-key"]!!))
        assertEquals("{}", request.body!!.utf8())
    }

    @Test
    fun readMoreRefusalsAreToldApartAndAnAnswerAboutAnotherFileIsNotTrusted() = runBlocking {
        server.enqueue(json(409, error("nothing_to_read")))
        assertEquals(ReadMoreOutcome.NothingToRead, api.readMore(work.attachmentId))
        server.enqueue(json(409, error("not_eligible")))
        assertEquals(ReadMoreOutcome.NotAvailable("not_eligible"), api.readMore(work.attachmentId))
        server.enqueue(json(404, error("not_found")))
        assertEquals(ReadMoreOutcome.NotAvailable("not_found"), api.readMore(work.attachmentId))
        server.enqueue(json(503, error("unavailable")))
        assertEquals(ReadMoreOutcome.Unavailable("unavailable"), api.readMore(work.attachmentId))
        server.enqueue(json(200, "{\"data\":{\"attachmentId\":\"${uuid(9)}\",\"state\":\"deferred\",\"detail\":\"device_unavailable\",\"resumePage\":101}}"))
        assertEquals(ReadMoreOutcome.Unavailable("malformed_response"), api.readMore(work.attachmentId))
        assertEquals(ReadMoreOutcome.NotAvailable("invalid_request"), api.readMore("../x"))
        signedIn = false
        assertEquals(ReadMoreOutcome.SessionExpired, api.readMore(work.attachmentId))
    }

    @Test
    fun anOutcomeIsRecordedOrTheLeaseIsTold() = runBlocking {
        server.enqueue(json(200, """{"data":{"leaseId":"${work.leaseId}","attachment":{"state":"unsupported","detail":"encrypted"}}}"""))
        assertEquals(OutcomeResult.Recorded, api.outcome(work, ExtractionOutcome.ENCRYPTED))
        val request = server.takeRequest()
        assertEquals("/api/v1/device-work/extraction/${work.leaseId}/outcome", request.url.encodedPath)
        assertEquals(ExtractionOutcome.ENCRYPTED, parseOutcomeRequest(wireJson.parseToJsonElement(request.body!!.utf8())).outcome)
        server.enqueue(json(409, error("lease_lost")))
        assertEquals(OutcomeResult.LeaseEnded("lease_lost"), api.outcome(work, ExtractionOutcome.FAILED))
        server.enqueue(json(500, error("internal")))
        assertEquals(OutcomeResult.Unavailable("internal"), api.outcome(work, ExtractionOutcome.UNAVAILABLE))
    }
}
