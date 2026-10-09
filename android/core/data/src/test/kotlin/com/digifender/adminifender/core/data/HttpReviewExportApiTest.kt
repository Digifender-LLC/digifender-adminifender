package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.AccessTokens
import com.digifender.adminifender.core.data.network.ConfirmResult
import com.digifender.adminifender.core.data.network.CreateExportResult
import com.digifender.adminifender.core.data.network.ExportAdmission
import com.digifender.adminifender.core.data.network.ExportFilter
import com.digifender.adminifender.core.data.network.ExportListResult
import com.digifender.adminifender.core.data.network.ExportRead
import com.digifender.adminifender.core.data.network.ExportScope
import com.digifender.adminifender.core.data.network.ExportState
import com.digifender.adminifender.core.data.network.HttpExportApi
import com.digifender.adminifender.core.data.network.HttpReviewApi
import com.digifender.adminifender.core.data.network.OpenResult
import com.digifender.adminifender.core.data.network.PrepareResult
import com.digifender.adminifender.core.data.network.PreparedReview
import com.digifender.adminifender.core.data.network.ReviewRead
import com.digifender.adminifender.core.data.network.ReviewState
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

private val S1 = uuid(11)
private val S2 = uuid(12)

class HttpReviewExportApiTest {
    private val server = MockWebServer()
    private val refused = mutableListOf<String>()
    private var signedIn = true
    private lateinit var reviews: HttpReviewApi
    private lateinit var exports: HttpExportApi

    @Before
    fun start() {
        server.start()
        val tokens = object : AccessTokens {
            override suspend fun current() = if (signedIn) "access-token" else null
            override suspend fun rejected(token: String) {
                refused += token
            }
        }
        val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
        reviews = HttpReviewApi(client, server.url("/"), tokens)
        exports = HttpExportApi(client, server.url("/"), tokens)
    }

    @After
    fun stop() = server.close()

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code).addHeader("content-type", "application/json").body(body).build()

    private fun data(contract: String, name: String) = """{"data":${contractFile("fixtures/v1/$contract/valid/$name.json").trim()}}"""

    private fun error(code: String, details: String = "null") = """{"error":{"code":"$code","message":"m","retryable":false,"details":$details}}"""

    @Test
    fun preparingADeletionSendsTheSortedIdsWithTheStoredKeyAndParsesTheExactReview() = runBlocking {
        server.enqueue(json(200, data("review-prepared", "archive-delete")))
        val out = reviews.prepareDeletion(uuid(300), listOf(S2, S1)) as PrepareResult.Prepared
        assertTrue(out.review is PreparedReview.Deletion)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/reviews", request.url.encodedPath)
        assertEquals("Bearer access-token", request.headers["authorization"])
        assertEquals(uuid(300), request.headers["idempotency-key"])
        assertEquals(Json.parseToJsonElement("""{"effect":"archive.delete","params":{"sourceIds":["$S1","$S2"]}}"""), Json.parseToJsonElement(request.body!!.utf8()))
    }

    @Test
    fun preparingNeverSendsAnEmptyOversizedOrMalformedSelection() = runBlocking {
        assertEquals(PrepareResult.Refused("invalid_request"), reviews.prepareDeletion(uuid(1), emptyList()))
        assertEquals(PrepareResult.Refused("invalid_request"), reviews.prepareDeletion(uuid(1), (1..51).map { uuid(it) }))
        assertEquals(PrepareResult.Refused("invalid_request"), reviews.prepareDeletion(uuid(1), listOf("../x")))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun preparationAnswersAreClassifiedByWhatTheyProve() = runBlocking {
        server.enqueue(json(401, error("unauthenticated")))
        assertEquals(PrepareResult.SessionExpired, reviews.prepareDeletion(uuid(1), listOf(S1)))
        assertEquals(listOf("access-token"), refused)
        server.enqueue(json(400, error("invalid_request")))
        assertEquals(PrepareResult.Refused("invalid_request"), reviews.prepareDeletion(uuid(1), listOf(S1)))
        server.enqueue(json(404, error("not_found")))
        assertEquals(PrepareResult.Refused("not_found"), reviews.prepareDeletion(uuid(1), listOf(S1)))
        server.enqueue(json(503, error("unavailable")))
        assertEquals(PrepareResult.NoAnswer("unavailable"), reviews.prepareDeletion(uuid(1), listOf(S1)))
        server.enqueue(json(409, error("request_in_progress")))
        assertEquals(PrepareResult.NoAnswer("request_in_progress"), reviews.prepareDeletion(uuid(1), listOf(S1)))
        server.enqueue(json(200, """{"data":{"reviewId":"x"}}"""))
        assertEquals(PrepareResult.NoAnswer("malformed_response"), reviews.prepareDeletion(uuid(1), listOf(S1)))
        server.enqueue(json(200, "not json"))
        assertEquals(PrepareResult.NoAnswer("malformed_response"), reviews.prepareDeletion(uuid(1), listOf(S1)))
        signedIn = false
        assertEquals(PrepareResult.SessionExpired, reviews.prepareDeletion(uuid(1), listOf(S1)))
    }

    @Test
    fun aLostConnectionIsNoAnswerNotARefusal() = runBlocking {
        server.close()
        assertEquals(PrepareResult.NoAnswer("network"), reviews.prepareDeletion(uuid(1), listOf(S1)))
        assertEquals(ConfirmResult.NoAnswer("network"), reviews.confirm(uuid(2), uuid(3), DIGEST))
        assertEquals(ReviewRead.Unavailable("network"), reviews.readReview(uuid(2)))
        assertEquals(CreateExportResult.NoAnswer("network"), exports.create(uuid(4), ExportScope.Archive(ExportAdmission.NORMAL)))
    }

    @Test
    fun confirmingSendsOnlyTheDigestWithTheStoredKey() = runBlocking {
        server.enqueue(json(200, data("review-confirmed", "archive-delete")))
        val out = reviews.confirm(uuid(2), uuid(3), DIGEST) as ConfirmResult.Confirmed
        assertTrue(out.confirmed.receipt is com.digifender.adminifender.core.data.network.OperationReceipt.Deletion)
        val request = server.takeRequest()
        assertEquals("/api/v1/reviews/${uuid(2)}/confirm", request.url.encodedPath)
        assertEquals(uuid(3), request.headers["idempotency-key"])
        assertEquals(Json.parseToJsonElement("""{"paramsDigest":"$DIGEST"}"""), Json.parseToJsonElement(request.body!!.utf8()))
    }

    @Test
    fun confirmationAnswersAreClassifiedAndOnlyDefiniteOnesAreRefusals() = runBlocking {
        server.enqueue(json(403, error("recent_auth_required")))
        assertEquals(ConfirmResult.RecentAuthRequired, reviews.confirm(uuid(2), uuid(3), DIGEST))
        server.enqueue(json(409, error("review_consumed", """{"operationId":"${uuid(50)}"}""")))
        assertEquals(ConfirmResult.AlreadyConsumed(uuid(50)), reviews.confirm(uuid(2), uuid(3), DIGEST))
        server.enqueue(json(409, error("stale_review")))
        assertEquals(ConfirmResult.Rejected("stale_review", 409), reviews.confirm(uuid(2), uuid(3), DIGEST))
        server.enqueue(json(410, error("review_expired")))
        assertEquals(ConfirmResult.Rejected("review_expired", 410), reviews.confirm(uuid(2), uuid(3), DIGEST))
        server.enqueue(json(409, error("review_mismatch")))
        assertEquals(ConfirmResult.Rejected("review_mismatch", 409), reviews.confirm(uuid(2), uuid(3), DIGEST))
        server.enqueue(json(503, error("ledger_unavailable")))
        assertEquals(ConfirmResult.NotCompleted("ledger_unavailable"), reviews.confirm(uuid(2), uuid(3), DIGEST))
        // Any other server error says nothing about whether the deletion happened.
        server.enqueue(json(500, error("internal")))
        assertEquals(ConfirmResult.NoAnswer("internal"), reviews.confirm(uuid(2), uuid(3), DIGEST))
        server.enqueue(json(429, error("rate_limited")))
        assertEquals(ConfirmResult.NoAnswer("rate_limited"), reviews.confirm(uuid(2), uuid(3), DIGEST))
        server.enqueue(json(409, error("request_in_progress")))
        assertEquals(ConfirmResult.NoAnswer("request_in_progress"), reviews.confirm(uuid(2), uuid(3), DIGEST))
        // A success whose body is not the contract's may well have committed.
        server.enqueue(json(200, """{"data":{"operationId":"${uuid(50)}","state":"succeeded"}}"""))
        assertEquals(ConfirmResult.NoAnswer("malformed_response"), reviews.confirm(uuid(2), uuid(3), DIGEST))
        server.enqueue(json(401, error("unauthenticated")))
        assertEquals(ConfirmResult.SessionExpired, reviews.confirm(uuid(2), uuid(3), DIGEST))
        // A review ID or digest that cannot be right never leaves the phone.
        val before = server.requestCount
        assertTrue(reviews.confirm("x", uuid(3), DIGEST) is ConfirmResult.Rejected)
        assertTrue(reviews.confirm(uuid(2), uuid(3), "short") is ConfirmResult.Rejected)
        assertEquals(before, server.requestCount)
    }

    @Test
    fun readbacksParseTheContractAndNotFoundIsNotProofOfAnything() = runBlocking {
        server.enqueue(json(200, data("review-readback", "consumed")))
        val read = (reviews.readReview(uuid(2)) as ReviewRead.Ok).value
        assertEquals(ReviewState.CONSUMED, read.state)
        assertEquals("GET", server.takeRequest().method)
        server.enqueue(json(404, error("not_found")))
        assertEquals(ReviewRead.NotKnown, reviews.readReview(uuid(2)))
        server.enqueue(json(410, error("gone")))
        assertEquals(ReviewRead.NotKnown, reviews.readReview(uuid(2)))
        server.enqueue(json(200, data("operation", "delete-cleanup-partial")))
        assertNotNull((reviews.readOperation(uuid(3)) as ReviewRead.Ok).value.cleanup)
        // A readback that leaks parameters is refused, not shown.
        server.enqueue(json(200, """{"data":${contractFile("fixtures/v1/review-readback/invalid/leaks-params.json").trim()}}"""))
        assertEquals(ReviewRead.Unavailable("malformed_response"), reviews.readReview(uuid(2)))
        server.enqueue(json(401, error("unauthenticated")))
        assertEquals(ReviewRead.SessionExpired, reviews.readReview(uuid(2)))
    }

    @Test
    fun creatingAnExportSendsTheExplicitScopeWithTheStoredKey() = runBlocking {
        server.enqueue(json(200, data("export-status", "pending-archive")))
        val scope = ExportScope.Filtered(ExportAdmission.ALL, ExportFilter(fromMs = 1_760_000_000_000, sender = "boss@example.com"))
        assertTrue(exports.create(uuid(4), scope) is CreateExportResult.Created)
        val request = server.takeRequest()
        assertEquals("/api/v1/exports", request.url.encodedPath)
        assertEquals(uuid(4), request.headers["idempotency-key"])
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(setOf("scope"), body.keys)
        assertEquals("all", body.getValue("scope").jsonObject.getValue("admission").let { (it as JsonPrimitive).content })
        assertEquals("filter", (body.getValue("scope").jsonObject.getValue("kind") as JsonPrimitive).content)
        // The default is spelled out, never left for the Worker to infer.
        server.enqueue(json(200, data("export-status", "pending-archive")))
        exports.create(uuid(5), ExportScope.Archive(ExportAdmission.NORMAL))
        assertEquals("normal", (Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject.getValue("scope").jsonObject.getValue("admission") as JsonPrimitive).content)
    }

    @Test
    fun exportAnswersAreClassified() = runBlocking {
        val scope = ExportScope.Archive(ExportAdmission.NORMAL)
        server.enqueue(json(409, error("idempotency_conflict")))
        assertEquals(CreateExportResult.Refused("idempotency_conflict"), exports.create(uuid(4), scope))
        server.enqueue(json(502, error("bad_gateway")))
        assertEquals(CreateExportResult.NoAnswer("bad_gateway"), exports.create(uuid(4), scope))
        server.enqueue(json(401, error("unauthenticated")))
        assertEquals(CreateExportResult.SessionExpired, exports.create(uuid(4), scope))
        server.enqueue(json(200, data("export-status", "ready-with-omissions")))
        assertEquals(ExportState.READY, (exports.status(uuid(9)) as ExportRead.Ok).status.state)
        server.enqueue(json(404, error("not_found")))
        assertEquals(ExportRead.NotKnown, exports.status(uuid(9)))
        // A ready export whose manifest cannot be read is reported with its code, never with empty counts.
        server.enqueue(json(500, error("export_manifest_unavailable")))
        assertEquals(ExportRead.Unavailable("export_manifest_unavailable"), exports.status(uuid(9)))
        server.enqueue(json(200, """{"data":${contractFile("fixtures/v1/export-status/invalid/ready-without-counts.json").trim()}}"""))
        assertEquals(ExportRead.Unavailable("malformed_response"), exports.status(uuid(9)))
        assertEquals(ExportRead.NotKnown, exports.status("nope"))
    }

    @Test
    fun theExportListIsAskedForWithItsLimitAndClassified() = runBlocking {
        server.enqueue(json(200, data("export-list", "ready-and-failed")))
        val listed = exports.list() as ExportListResult.Ok
        assertEquals(listOf(ExportState.READY, ExportState.FAILED), listed.exports.map { it.state })
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/exports", request.url.encodedPath)
        assertEquals("50", request.url.queryParameter("limit"))
        assertTrue(request.headers["authorization"]!!.startsWith("Bearer "))
        server.enqueue(json(401, error("unauthenticated")))
        assertEquals(ExportListResult.SessionExpired, exports.list())
        server.enqueue(json(503, error("unavailable")))
        assertEquals(ExportListResult.Unavailable("unavailable"), exports.list())
        // A list that breaks the contract is reported as such, never partly used.
        server.enqueue(json(200, """{"data":${contractFile("fixtures/v1/export-list/invalid/ready-without-counts.json").trim()}}"""))
        assertEquals(ExportListResult.Unavailable("malformed_response"), exports.list())
    }

    @Test
    fun anAttachmentStreamsWithItsLengthAndDeclaredChecksum() = runBlocking {
        val bytes = ByteArray(200_000) { (it % 200).toByte() }
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("content-type", "application/octet-stream").addHeader("x-attachment-sha256", sha256Of(bytes).uppercase())
                .addHeader("x-content-type-options", "nosniff").body(okio.Buffer().write(bytes)).build(),
        )
        val open = exports.openAttachment(uuid(70)) as OpenResult.Open
        val request = server.takeRequest()
        assertEquals("/api/v1/attachments/${uuid(70)}/content", request.url.encodedPath)
        assertEquals("Bearer access-token", request.headers["authorization"])
        assertEquals(bytes.size.toLong(), open.stream.contentLength)
        // The checksum header is normalized to lower case so it compares with the inventory's.
        assertEquals(sha256Of(bytes), open.stream.declaredSha256)
        val read = withContext(Dispatchers.IO) { open.stream.use { it.body.readBytes() } }
        assertEquals(bytes.toList(), read.toList())
    }

    @Test
    fun contentAnswersAreClassified() = runBlocking {
        server.enqueue(json(404, error("not_found")))
        assertEquals(OpenResult.Gone("not_found", 404), exports.openAttachment(uuid(70)))
        server.enqueue(json(410, error("expired")))
        assertEquals(OpenResult.Gone("expired", 410), exports.openContent(uuid(71)))
        server.enqueue(json(409, error("not_ready")))
        assertEquals(OpenResult.Unavailable("not_ready"), exports.openContent(uuid(71)))
        server.enqueue(json(409, error("export_failed")))
        assertEquals(OpenResult.Refused("export_failed", 409), exports.openContent(uuid(71)))
        server.enqueue(json(503, error("unavailable")))
        assertEquals(OpenResult.Unavailable("unavailable"), exports.openAttachment(uuid(70)))
        server.enqueue(json(401, error("unauthenticated")))
        assertEquals(OpenResult.SessionExpired, exports.openAttachment(uuid(70)))
        assertEquals(OpenResult.Gone("not_found", 404), exports.openAttachment("../etc"))
    }

    @Test
    fun abortingAStreamWakesAReadBlockedOnTheNetwork() = runBlocking {
        val bytes = ByteArray(2_000_000)
        server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(bytes)).throttleBody(1024, 200, TimeUnit.MILLISECONDS).build())
        val open = exports.openContent(uuid(71)) as OpenResult.Open
        val reader = async(Dispatchers.IO) {
            try {
                val buffer = ByteArray(4096)
                while (open.stream.body.read(buffer) >= 0) Unit
                "finished"
            } catch (_: IOException) {
                "interrupted"
            }
        }
        kotlinx.coroutines.delay(300)
        open.stream.abort()
        assertEquals("interrupted", withTimeout(10_000) { reader.await() })
    }

    @Test
    fun cancellingTheCallerClosesTheRequestInsteadOfWaitingForTheServer() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).headersDelay(30, TimeUnit.SECONDS).build())
        val started = System.nanoTime()
        val call = async(Dispatchers.Default) { exports.openAttachment(uuid(70)) }
        kotlinx.coroutines.delay(300)
        call.cancel()
        try {
            withTimeout(10_000) { call.await() }
            fail("expected cancellation")
        } catch (_: CancellationException) {
            // as it must
        }
        assertTrue("cancelled promptly, not after the server's delay", TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 10)
    }

    private fun partial(code: Int, contentRange: String?, body: String): MockResponse {
        val builder = MockResponse.Builder().code(code).addHeader("content-type", "application/zip").body(body)
        if (contentRange != null) builder.addHeader("content-range", contentRange)
        return builder.build()
    }

    @Test
    fun theRestOfAnExportIsAskedForWithAByteRangeAndOnlyAnExactTailIsAccepted() = runBlocking {
        server.enqueue(partial(206, "bytes 4-9/10", "456789"))
        val open = exports.openContentFrom(S1, 4) as OpenResult.Open
        assertEquals(6L, open.stream.contentLength)
        assertEquals("456789", open.stream.body.readBytes().decodeToString())
        open.stream.close()
        val request = server.takeRequest()
        assertEquals("/api/v1/exports/$S1/content", request.url.encodedPath)
        assertEquals("bytes=4-", request.headers["range"])
        assertEquals("Bearer access-token", request.headers["authorization"])

        // A 200 means the Worker ignored the range; a tail that starts elsewhere or does not reach the end is not the rest of the file.
        server.enqueue(partial(200, null, "0123456789"))
        assertEquals(OpenResult.Unavailable("range_not_honoured"), exports.openContentFrom(S1, 4))
        server.enqueue(partial(206, "bytes 5-9/10", "56789"))
        assertEquals(OpenResult.Unavailable("range_not_honoured"), exports.openContentFrom(S1, 4))
        server.enqueue(partial(206, "bytes 4-8/10", "45678"))
        assertEquals(OpenResult.Unavailable("range_not_honoured"), exports.openContentFrom(S1, 4))
        server.enqueue(partial(206, "bytes 4-9/10", "4567"))
        assertEquals(OpenResult.Unavailable("range_not_honoured"), exports.openContentFrom(S1, 4))
        server.enqueue(partial(206, null, "456789"))
        assertEquals(OpenResult.Unavailable("range_not_honoured"), exports.openContentFrom(S1, 4))
    }

    @Test
    fun aRangeThatCannotBeHonouredOrAnExportThatIsGoneIsToldApart() = runBlocking {
        server.enqueue(json(416, error("range_not_satisfiable")))
        assertEquals(OpenResult.Refused("range_not_satisfiable", 416), exports.openContentFrom(S1, 4))
        server.enqueue(json(410, error("expired")))
        assertEquals(OpenResult.Gone("expired", 410), exports.openContentFrom(S1, 4))
        server.enqueue(json(503, error("unavailable")))
        assertEquals(OpenResult.Unavailable("unavailable"), exports.openContentFrom(S1, 4))
        // Nothing is sent for an offset that is not a continuation or an ID that is not one.
        val before = server.requestCount
        assertEquals(OpenResult.Gone("not_found", 404), exports.openContentFrom(S1, 0))
        assertEquals(OpenResult.Gone("not_found", 404), exports.openContentFrom("../x", 4))
        assertEquals(before, server.requestCount)
    }
}
