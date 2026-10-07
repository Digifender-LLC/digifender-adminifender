package com.digifender.adminifender.core.data.chat

import com.digifender.adminifender.core.data.network.AccessTokens
import com.digifender.adminifender.core.data.network.ReadResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpChatApiTest {
    private val server = MockWebServer()
    private val thread = "11111111-1111-4111-8111-111111111111"
    private val turn = "22222222-2222-4222-8222-222222222222"
    private val source = "33333333-3333-4333-8333-333333333333"
    private val rejected = mutableListOf<String>()
    private lateinit var api: HttpChatApi

    @Before
    fun start() {
        server.start()
        api = HttpChatApi(OkHttpClient(), server.url("/"), object : AccessTokens {
            override suspend fun current() = "synthetic-access-token"
            override suspend fun rejected(token: String) { rejected += token }
        })
    }

    @After
    fun stop() = server.close()

    private fun response(code: Int, body: String) = MockResponse.Builder().code(code).addHeader("content-type", "application/json").body(body).build()

    @Test
    fun scopeAndTurnBodyMatchBackendAndPreserveIdentity() = runTest {
        server.enqueue(response(200, """{"data":{"id":"$thread","scope":{"kind":"source","id":"$source"},"createdAt":"2026-10-07T10:00:00Z"}}"""))
        assertTrue(api.createThread(ChatScope("source", source), "synthetic-create-key") is ChatWriteResult.Ok)
        val creation = server.takeRequest()
        assertEquals("/api/v1/chat/threads", creation.url.encodedPath)
        assertEquals("Bearer synthetic-access-token", creation.headers["authorization"])
        assertEquals("synthetic-create-key", creation.headers["idempotency-key"])
        assertEquals(Json.parseToJsonElement("""{"scope":{"kind":"source","id":"$source"}}"""), Json.parseToJsonElement(creation.body!!.utf8()))
        server.enqueue(response(202, """{"data":{"turnId":"$turn","state":"pending"}}"""))
        assertEquals(ChatWriteResult.Ok(ChatAccepted(turn, "pending")), api.submitTurn(thread, turn, "Synthetic question?", turn))
        val request = server.takeRequest()
        assertEquals("/api/v1/chat/threads/$thread/turns", request.url.encodedPath)
        assertEquals(turn, request.headers["idempotency-key"])
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(setOf("turnId", "content"), body.keys)
        assertEquals(turn, body.getValue("turnId").jsonPrimitive.content)
        assertEquals("Synthetic question?", body.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun defaultScopeAlwaysSendsRequiredAllKind() = runTest {
        server.enqueue(response(200, """{"data":{"id":"$thread","scope":{"kind":"all"},"createdAt":"2026-10-07T10:00:00Z"}}"""))
        assertTrue(api.createThread(ChatScope(), "synthetic-create-key") is ChatWriteResult.Ok)
        assertEquals(Json.parseToJsonElement("""{"scope":{"kind":"all"}}"""), Json.parseToJsonElement(server.takeRequest().body!!.utf8()))
    }

    @Test
    fun responseLossAndMalformedSuccessAreUnknownOutcomes() = runTest {
        server.enqueue(response(503, """{"error":{"code":"unavailable"}}"""))
        assertEquals(ChatWriteResult.NoAnswer, api.submitTurn(thread, turn, "Question", turn))
        server.enqueue(response(202, """{"data":{"unknown":true}}"""))
        assertEquals(ChatWriteResult.NoAnswer, api.submitTurn(thread, turn, "Question", turn))
        server.enqueue(response(202, "{}"))
        assertEquals(ChatWriteResult.NoAnswer, api.submitTurn(thread, turn, "Question", turn))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun inProgressAndUnreadableRefusalsAreUnknownOutcomes() = runTest {
        server.enqueue(response(409, """{"error":{"code":"request_in_progress"}}"""))
        assertEquals(ChatWriteResult.NoAnswer, api.submitTurn(thread, turn, "Question", turn))
        server.enqueue(response(400, "not-json"))
        assertEquals(ChatWriteResult.NoAnswer, api.submitTurn(thread, turn, "Question", turn))
    }

    @Test
    fun cancellationRefusalAndAuthenticationLossRemainExplicit() = runTest {
        server.enqueue(response(409, """{"error":{"code":"already_dispatched"}}"""))
        assertEquals(ChatWriteResult.Rejected("already_dispatched"), api.cancel(turn, "cancel-key"))
        assertEquals("/api/v1/chat/turns/$turn/cancel", server.takeRequest().url.encodedPath)
        server.enqueue(response(401, """{"error":{"code":"unauthenticated"}}"""))
        assertEquals(ReadResult.SessionExpired, api.turn(turn))
        assertEquals(listOf("synthetic-access-token"), rejected)
    }

    @Test
    fun historyUsesBeforeTurnAndDecodesWithheldAnswers() = runTest {
        server.enqueue(response(200, """{"data":{"thread":{"id":"$thread","scope":{"kind":"all"},"createdAt":"2026-10-07T10:00:00Z"},"turns":[{"turnId":"$turn","threadId":"$thread","scope":{"kind":"all"},"content":"Question?","state":"complete","answer":null,"claims":[],"gaps":[],"proposals":[],"invalidated":false,"withheld":true,"createdAt":"2026-10-07T10:00:00Z"}],"hasMore":true}}"""))
        val result = api.history(thread, turn) as ReadResult.Ok
        assertTrue(result.value.turns.single().withheld)
        assertTrue(result.value.hasMore)
        assertEquals(turn, server.takeRequest().url.queryParameter("before"))
    }

    @Test
    fun invalidPathsAndScopeAreRefusedBeforeAnyRequest() = runTest {
        assertEquals(ReadResult.Unavailable("not_found"), api.turn("../../tokens"))
        assertEquals(ChatWriteResult.Rejected("invalid_scope"), api.createThread(ChatScope("all", source), "key"))
        assertEquals(ChatWriteResult.Rejected("invalid_scope"), api.createThread(ChatScope("source"), "key"))
        assertEquals(ChatWriteResult.Rejected("invalid_request"), api.submitTurn(thread, turn, "x".repeat(4_001), "key"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun delayedBodyIsReadAndClosedAwayFromTheUiCaller() = runTest {
        val bodyThreads = CopyOnWriteArrayList<String>()
        var closed = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val original = response.body
            val wrapped = object : ResponseBody() {
                private val source = object : ForwardingSource(original.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        bodyThreads += Thread.currentThread().name
                        return super.read(sink, byteCount)
                    }
                    override fun close() { closed = true; super.close() }
                }.buffer()
                override fun contentType() = original.contentType()
                override fun contentLength() = original.contentLength()
                override fun source(): BufferedSource = source
            }
            response.newBuilder().body(wrapped).build()
        }.build()
        val transport = HttpChatApi(client, server.url("/"), AccessTokens { "synthetic-access-token" })
        server.enqueue(MockResponse.Builder().code(202).bodyDelay(50, TimeUnit.MILLISECONDS).body("""{"data":{"turnId":"$turn","state":"pending"}}""").build())
        Executors.newSingleThreadExecutor { Thread(it, "synthetic-ui-caller") }.asCoroutineDispatcher().use { caller ->
            withContext(caller) { assertTrue(transport.submitTurn(thread, turn, "Question", turn) is ChatWriteResult.Ok) }
        }
        assertTrue(bodyThreads.isNotEmpty())
        assertTrue(bodyThreads.none { it == "synthetic-ui-caller" })
        assertTrue(closed)
    }

    @Test
    fun redirectsDoNotForwardQuestionContent() = runTest {
        server.enqueue(MockResponse.Builder().code(307).addHeader("location", server.url("/elsewhere").toString()).body("{}").build())
        assertEquals(ChatWriteResult.NoAnswer, api.submitTurn(thread, turn, "Synthetic question", turn))
        assertEquals(1, server.requestCount)
    }
}
