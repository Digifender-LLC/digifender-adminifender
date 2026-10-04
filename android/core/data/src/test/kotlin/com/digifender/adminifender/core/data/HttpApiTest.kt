package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.AccessTokens
import com.digifender.adminifender.core.data.network.ArchiveView
import com.digifender.adminifender.core.data.network.SearchRequest
import com.digifender.adminifender.core.data.network.HttpApi
import com.digifender.adminifender.core.data.network.ReadResult
import com.digifender.adminifender.core.model.ConcernAction
import com.digifender.adminifender.core.model.SendResult
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
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
}
