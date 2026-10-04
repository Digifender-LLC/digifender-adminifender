package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.AccessTokens
import com.digifender.adminifender.core.data.network.ArchiveView
import com.digifender.adminifender.core.data.network.SearchRequest
import com.digifender.adminifender.core.data.network.HttpApi
import com.digifender.adminifender.core.data.network.TurnStateDto
import com.digifender.adminifender.core.data.network.ScopeDto
import com.digifender.adminifender.core.data.network.CallResult
import com.digifender.adminifender.core.data.network.ReadResult
import com.digifender.adminifender.core.model.ConcernAction
import com.digifender.adminifender.core.model.SendResult
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpApiTest {
    private val server = MockWebServer()
    private lateinit var api: HttpApi
    private val id = "0b4e6f8a-2c3d-4e5f-8a9b-1c2d3e4f5a6b"

    @Before
    fun start() {
        server.start()
        val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
        api = HttpApi(client, server.url("/")) { "access-token" }
    }

    @After
    fun stop() = server.close()

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code).addHeader("content-type", "application/json").body(body).build()

    @Test
    fun readsAChangesPageWithBearerAuth() = runTest {
        server.enqueue(json(200, """{"data":${contractFile("fixtures/v1/sync-changes/valid/concern-source-tombstone.json")},"requestId":"r"}"""))
        val result = api.changes(40, 200) as ReadResult.Ok
        assertEquals(4, result.value.changes.size)
        val request = server.takeRequest()
        assertEquals("/api/v1/sync/changes?after=40&limit=200", request.target)
        assertEquals("Bearer access-token", request.headers["authorization"])
    }

    @Test
    fun mapsExpiredCursorsAndSessions() = runTest {
        server.enqueue(json(410, """{"error":{"code":"cursor_expired","message":"m"}}"""))
        server.enqueue(json(401, """{"error":{"code":"unauthorized","message":"m"}}"""))
        server.enqueue(json(503, """{"error":{"code":"unavailable","message":"m"}}"""))
        assertEquals(ReadResult.CursorExpired, api.changes(1, 10))
        assertEquals(ReadResult.SessionExpired, api.changes(1, 10))
        assertEquals(ReadResult.Unavailable("unavailable"), api.changes(1, 10))
    }

    @Test
    fun sendsTheExactActionBodyWithItsIdempotencyKey() = runTest {
        server.enqueue(json(200, """{"data":{"concern":{"id":"$id","revision":5},"eventId":"e","undoableUntil":null}}"""))
        val result = api.concernAction(id, "00000000-0000-4000-8000-000000000001", 4, ConcernAction.Snooze(1_791_028_800_000))
        assertEquals(SendResult.Accepted(5), result)
        val request = server.takeRequest()
        assertEquals("/api/v1/concerns/$id/actions", request.target)
        assertEquals("00000000-0000-4000-8000-000000000001", request.headers["idempotency-key"])
        assertEquals("""{"expectedRevision":4,"kind":"snooze","args":{"until":"2026-10-03T12:00:00Z"}}""", request.body?.utf8())
    }

    @Test
    fun distinguishesStaleRejectedAndUnansweredSends() = runTest {
        server.enqueue(json(409, """{"error":{"code":"stale_revision","message":"m","details":{"currentRevision":9}}}"""))
        server.enqueue(json(422, """{"error":{"code":"invalid_transition","message":"m"}}"""))
        server.enqueue(json(503, """{"error":{"code":"unavailable","message":"m"}}"""))
        assertEquals(SendResult.StaleRevision(9), api.concernAction(id, "k", 4, ConcernAction.Acknowledge))
        assertEquals(SendResult.Rejected("invalid_transition"), api.concernAction(id, "k", 4, ConcernAction.Acknowledge))
        assertEquals(SendResult.NoAnswer, api.concernAction(id, "k", 4, ConcernAction.Acknowledge))
        server.close()
        assertEquals(SendResult.NoAnswer, api.concernAction(id, "k", 4, ConcernAction.Acknowledge))
    }

    @Test
    fun withoutATokenNothingIsSent() = runTest {
        val signedOut = HttpApi(OkHttpClient(), server.url("/")) { null }
        assertEquals(ReadResult.SessionExpired, signedOut.changes(0, 10))
        assertEquals(SendResult.SessionExpired, signedOut.concernAction(id, "k", 1, ConcernAction.Acknowledge))
        assertTrue(server.requestCount == 0)
    }

    @Test
    fun aRefusedTokenIsReportedToTheSessionLayer() = runTest {
        val refused = mutableListOf<String>()
        val tokens = object : AccessTokens {
            override suspend fun current() = "access-token"
            override suspend fun rejected(token: String) {
                refused += token
            }
        }
        val reporting = HttpApi(OkHttpClient(), server.url("/"), tokens)
        server.enqueue(json(200, """{"data":{"changes":[],"throughSeq":1,"hasMore":false},"requestId":"r"}"""))
        reporting.changes(1, 10)
        assertEquals(emptyList<String>(), refused)
        server.enqueue(json(401, """{"error":{"code":"unauthenticated","message":"m"}}"""))
        assertEquals(SendResult.SessionExpired, reporting.concernAction(id, "k", 1, ConcernAction.Acknowledge))
        assertEquals(listOf("access-token"), refused)
    }
    @Test
    fun searchSendsOnlyTheFiltersGivenAsQueryParameters() = runTest {
        server.enqueue(json(200, """{"data":{"results":[{"sourceId":"$id","chunkId":"c","attachmentId":null,"partitionId":1,"subject":"S","snippet":"a [b]","page":null,"startOffset":0,"receivedAt":"2026-09-21T10:00:00.000Z"}],"partitionsRemaining":0,"nextCursor":null,"snapshot":5}}"""))
        val page = api.search(SearchRequest("water & bill", ArchiveView.QUARANTINE, "a@b.example", 1_790_000_000_000, null), null, 50) as ReadResult.Ok
        assertEquals(1, page.value.results.size)
        val target = server.takeRequest().target
        assertEquals("/api/v1/search?q=water%20%26%20bill&view=quarantine&sender=a%40b.example&from=2026-09-21T14%3A13%3A20Z&mode=exact&limit=50", target)
    }

    @Test
    fun readsSourceDetailAndChunkPages() = runTest {
        server.enqueue(json(200, """{"data":{"source":{"id":"$id","kind":"email","state":"ready","admission":"normal","privacy":"hybrid","revision":2,"receivedAt":"2026-09-21T10:00:00.000Z","subject":"S","from":["a@b.example"],"to":[]},"text":null,"textInChunks":true,"provenance":{"parserVersion":"p"},"attachments":[{"id":"a","name":"n.pdf","declaredType":null,"sizeBytes":5,"sha256":"x","disposition":"attachment","extraction":"stored","extractionDetail":null}]}}"""))
        server.enqueue(json(200, """{"data":{"chunks":[{"chunkId":"c","attachmentId":null,"ordinal":0,"page":null,"startOffset":0,"text":"hi"}],"nextCursor":"n"}}"""))
        val detail = (api.source(id) as ReadResult.Ok).value
        assertTrue(detail.textInChunks)
        assertEquals("n.pdf", detail.attachments.single().name)
        assertEquals("n", (api.chunks(id, "cur", 200) as ReadResult.Ok).value.nextCursor)
        assertEquals("/api/v1/sources/$id", server.takeRequest().target)
        assertEquals("/api/v1/sources/$id/chunks?cursor=cur&limit=200", server.takeRequest().target)
    }

    @Test
    fun aSourceIdThatIsNotAnIdIsNeverPutInAPath() = runTest {
        assertEquals(ReadResult.Unavailable("not_found"), api.source("../sync/changes"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aChatTurnUsesItsTurnIdAsTheIdempotencyKey() = runTest {
        server.enqueue(json(202, """{"data":{"turnId":"$id","state":"pending"}}"""))
        server.enqueue(json(409, """{"error":{"code":"idempotency_conflict","message":"m"}}"""))
        server.enqueue(json(503, """{"error":{"code":"unavailable","message":"m"}}"""))
        assertEquals(CallResult.Ok(TurnStateDto(id, "pending")), api.postTurn(id, id, "When?"))
        assertEquals(CallResult.Refused("idempotency_conflict"), api.postTurn(id, id, "When?"))
        assertEquals(CallResult.NoAnswer, api.postTurn(id, id, "When?"))
        val request = server.takeRequest()
        assertEquals("/api/v1/chat/threads/$id/turns", request.target)
        assertEquals(id, request.headers["idempotency-key"])
        assertEquals("""{"turnId":"$id","content":"When?"}""", request.body?.utf8())
    }

    @Test
    fun createsAScopedThread() = runTest {
        server.enqueue(json(200, """{"data":{"id":"$id","scope":{"kind":"source","id":"$id"},"createdAt":"2026-10-03T12:00:00.000Z"}}"""))
        val result = api.createThread("00000000-0000-4000-8000-000000000001", ScopeDto("source", id)) as CallResult.Ok
        assertEquals("source", result.value.scope.kind)
        assertEquals("""{"scope":{"kind":"source","id":"$id"}}""", server.takeRequest().body?.utf8())
    }

    @Test
    fun claimsWithExplicitNullsAndSubmitsUnderTheLease() = runTest {
        server.enqueue(json(200, """{"data":{"work":null,"reason":"not_ready"}}"""))
        server.enqueue(json(200, """{"data":{"attemptId":"$id","state":"succeeded"}}"""))
        val readiness = com.digifender.adminifender.core.model.Readiness(false, true, 55, com.digifender.adminifender.core.model.Thermal.LIGHT, null, null)
        assertEquals(CallResult.Ok(com.digifender.adminifender.core.data.device.ClaimDto(null, "not_ready")), api.claim("00000000-0000-4000-8000-000000000001", readiness))
        assertEquals(
            """{"foreground":false,"charging":true,"batteryPercent":55,"thermal":"light","modelId":null,"modelManifestHash":null}""",
            server.takeRequest().body?.utf8(),
        )
        api.submit(id, "00000000-0000-4000-8000-000000000002", com.digifender.adminifender.core.data.device.WorkResult("lease", "d", 4, "{}", "h"))
        val submit = server.takeRequest()
        assertEquals("/api/v1/device-work/$id/result", submit.target)
        assertEquals("""{"leaseToken":"lease","inputDigest":"d","policyRevision":4,"outputJson":"{}","modelManifestHash":"h"}""", submit.body?.utf8())
    }

    @Test
    fun reviewsAreCreatedAndConfirmedWithTheirDigest() = runTest {
        server.enqueue(json(200, """{"data":{"reviewId":"$id","effect":"archive.delete","params":{},"paramsDigest":"${"d".repeat(64)}","preconditions":{},"preview":{"expectedCount":1},"expiresAt":"2026-10-04T00:10:00.000Z","requiresRecentAuth":true}}"""))
        server.enqueue(json(403, """{"error":{"code":"recent_auth_required","message":"m"}}"""))
        val review = (api.createReview("00000000-0000-4000-8000-000000000001", "archive.delete", kotlinx.serialization.json.buildJsonObject { put("sourceIds", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(id)))) }) as CallResult.Ok).value
        assertEquals(CallResult.Refused("recent_auth_required"), api.confirmReview(review.reviewId, "00000000-0000-4000-8000-000000000002", review.paramsDigest))
        assertEquals("""{"effect":"archive.delete","params":{"sourceIds":["$id"]}}""", server.takeRequest().body?.utf8())
        assertEquals("""{"paramsDigest":"${"d".repeat(64)}"}""", server.takeRequest().body?.utf8())
    }

    @Test
    fun aSavedAttachmentMustMatchItsHashAndSize() = runTest {
        val bytes = "attachment".toByteArray()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        repeat(3) { server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(bytes)).build()) }
        val out = java.io.ByteArrayOutputStream()
        assertEquals(com.digifender.adminifender.core.data.network.StreamResult.OK, api.saveAttachment(id, out, 100, sha))
        assertEquals("attachment", out.toString())
        assertEquals(com.digifender.adminifender.core.data.network.StreamResult.WRONG_HASH, api.saveAttachment(id, java.io.ByteArrayOutputStream(), 100, "0".repeat(64)))
        assertEquals(com.digifender.adminifender.core.data.network.StreamResult.TOO_LARGE, api.saveAttachment(id, java.io.ByteArrayOutputStream(), 4, sha))
    }
}
