package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.AccessTokens
import com.digifender.adminifender.core.data.network.BriefingReviewResult
import com.digifender.adminifender.core.data.network.HttpBriefingApi
import com.digifender.adminifender.core.data.network.ReadResult
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val ID = "00000000-0000-4000-8000-000000000001"

class HttpBriefingApiTest {
    private val server = MockWebServer()
    private var access: String? = "synthetic-token"
    private var rejected: String? = null
    private val tokens = object : AccessTokens {
        override suspend fun current() = access
        override suspend fun rejected(token: String) { rejected = token }
    }
    private lateinit var api: HttpBriefingApi

    @Before fun start() {
        server.start()
        api = HttpBriefingApi(OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build(), server.url("/"), tokens)
    }
    @After fun close() = server.close()

    @Test
    fun loadsDeterministicSectionsAndReviewPostsOnlySnapshotIdAndStableKey() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"data":{"id":"$ID","snapshotSeq":17,"throughTime":"2026-10-07T09:00:00Z","lastReviewedAt":null,"newConcerns":[],"changedConcerns":[],"overdue":[],"unacknowledged":[],"newSourceIds":[],"summary":null,"hasMore":true}}""").build())
        val current = api.current() as ReadResult.Ok
        assertTrue(current.value.hasMore)
        val get = server.takeRequest()
        assertEquals("/api/v1/briefings/current", get.url.encodedPath)
        assertEquals("Bearer synthetic-token", get.headers["authorization"])
        server.enqueue(MockResponse.Builder().code(503).body("""{"error":{"code":"busy"}}""").build())
        assertEquals(BriefingReviewResult.NoAnswer, api.review(ID, "review-key"))
        server.enqueue(MockResponse.Builder().body("""{"data":{"id":"$ID","reviewedThroughSeq":17,"reviewedAt":"2026-10-07T09:01:00Z"}}""").build())
        assertTrue(api.review(ID, "review-key") is BriefingReviewResult.Reviewed)
        repeat(2) {
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/briefings/$ID/review", request.url.encodedPath)
            assertEquals("review-key", request.headers["idempotency-key"])
            assertEquals("{}", request.body!!.utf8())
        }
    }

    @Test
    fun signOutAndUnsafeIdentifiersNeverSendARequestAndRefusedTokensBecomeStale() = runTest {
        access = null
        assertEquals(ReadResult.SessionExpired, api.current())
        assertEquals(BriefingReviewResult.SessionExpired, api.review(ID, "key"))
        access = "synthetic-token"
        assertEquals(BriefingReviewResult.Rejected("not_found"), api.review("../../sources", "key"))
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse.Builder().code(401).body("untrusted response").build())
        assertEquals(BriefingReviewResult.SessionExpired, api.review(ID, "key"))
        assertEquals("synthetic-token", rejected)
    }

    @Test
    fun malformedSuccessIsUncertainAndAlreadyReviewedIsRecognized() = runTest {
        server.enqueue(MockResponse.Builder().body("{}").build())
        assertEquals(BriefingReviewResult.NoAnswer, api.review(ID, "key"))
        server.enqueue(MockResponse.Builder().code(409).body("""{"error":{"code":"already_reviewed"}}""").build())
        assertEquals(BriefingReviewResult.AlreadyReviewed, api.review(ID, "key"))
        server.enqueue(MockResponse.Builder().body("{}").build())
        assertEquals(ReadResult.Unavailable("malformed_response"), api.current())
    }
}
