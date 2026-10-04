package com.digifender.adminifender.core.data

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
}
