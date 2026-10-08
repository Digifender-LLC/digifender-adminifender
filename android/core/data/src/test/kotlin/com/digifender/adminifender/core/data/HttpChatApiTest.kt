package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.CancelResult
import com.digifender.adminifender.core.data.network.ChatScope
import com.digifender.adminifender.core.data.network.HttpApi
import com.digifender.adminifender.core.data.network.PostResult
import com.digifender.adminifender.core.data.network.ReadResult
import com.digifender.adminifender.core.data.network.ScopeKind
import com.digifender.adminifender.core.data.network.ThreadResult
import com.digifender.adminifender.core.data.repository.ClaimBasis
import com.digifender.adminifender.core.data.repository.ContextSource
import com.digifender.adminifender.core.data.repository.ServerState
import com.digifender.adminifender.core.data.repository.toServerTurn
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The chat calls against the wire shapes backend/src/chat/routes.ts actually produces. */
class HttpChatApiTest {
    private val server = MockWebServer()
    private lateinit var api: HttpApi
    private val concern = "0b4e6f8a-2c3d-4e5f-8a9b-1c2d3e4f5a6b"
    private val thread = "7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f"
    private val turn = "11111111-1111-4111-8111-111111111111"
    private val key = "22222222-2222-4222-8222-222222222222"

    @Before
    fun start() {
        server.start()
        api = HttpApi(OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build(), server.url("/")) { "access-token" }
    }

    @After
    fun stop() = server.close()

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code).addHeader("content-type", "application/json").body(body).build()

    private fun error(code: Int, name: String, details: String = "{}") = json(code, """{"error":{"code":"$name","message":"m","retryable":false,"details":$details}}""")

    @Test
    fun createsAThreadWithTheFixedKeyAndTheExactScopeBody() = runTest {
        server.enqueue(json(200, """{"data":{"id":"$thread","scope":{"kind":"concern","id":"$concern"},"createdAt":"2026-10-08T10:00:00.000Z"},"requestId":"r"}"""))
        val result = api.createThread(key, ChatScope(ScopeKind.CONCERN, concern)) as ThreadResult.Created
        assertEquals(thread, result.thread.id)
        assertEquals(concern, result.thread.scope.id)
        val request = server.takeRequest()
        assertEquals("/api/v1/chat/threads", request.target)
        assertEquals(key, request.headers["idempotency-key"])
        assertEquals("Bearer access-token", request.headers["authorization"])
        assertEquals("""{"scope":{"kind":"concern","id":"$concern"}}""", request.body?.utf8())

        server.enqueue(json(200, """{"data":{"id":"$thread","scope":{"kind":"all"},"createdAt":"2026-10-08T10:00:00.000Z"}}"""))
        api.createThread(key, ChatScope(ScopeKind.ALL))
        assertEquals("""{"scope":{"kind":"all"}}""", server.takeRequest().body?.utf8())
    }

    @Test
    fun separatesAThreadThatCannotExistFromOneThatIsUnknown() = runTest {
        server.enqueue(error(404, "not_found"))
        server.enqueue(error(422, "scope_unavailable"))
        server.enqueue(error(409, "request_in_progress"))
        server.enqueue(error(503, "unavailable"))
        server.enqueue(error(401, "unauthenticated"))
        server.enqueue(json(200, """{"data":{"unexpected":true}}"""))
        val scope = ChatScope(ScopeKind.CONCERN, concern)
        assertEquals(ThreadResult.Refused("not_found"), api.createThread(key, scope))
        assertEquals(ThreadResult.Refused("scope_unavailable"), api.createThread(key, scope))
        assertEquals(ThreadResult.NoAnswer, api.createThread(key, scope))
        assertEquals(ThreadResult.NoAnswer, api.createThread(key, scope))
        assertEquals(ThreadResult.SessionExpired, api.createThread(key, scope))
        assertEquals(ThreadResult.NoAnswer, api.createThread(key, scope))
        server.close()
        assertEquals(ThreadResult.NoAnswer, api.createThread(key, scope))
    }

    @Test
    fun aMalformedScopeNeverLeavesThePhone() = runTest {
        assertEquals(ThreadResult.Refused("invalid_scope"), api.createThread(key, ChatScope(ScopeKind.CONCERN, null)))
        assertEquals(ThreadResult.Refused("invalid_scope"), api.createThread(key, ChatScope(ScopeKind.CONCERN, "../x")))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun postsOnlyTheTurnIdAndTheContentUsingTheTurnIdAsTheKey() = runTest {
        server.enqueue(json(202, """{"data":{"turnId":"$turn","state":"pending"}}"""))
        assertEquals(PostResult.Accepted("pending"), api.postTurn(thread, turn, "Did I \"pay\"?\n"))
        val request = server.takeRequest()
        assertEquals("/api/v1/chat/threads/$thread/turns", request.target)
        assertEquals(turn, request.headers["idempotency-key"])
        // The Worker rejects unknown fields, so no scope revision or anything else is sent.
        assertEquals("""{"turnId":"$turn","content":"Did I \"pay\"?\n"}""", request.body?.utf8())
    }

    @Test
    fun aPostAnswerIsConfirmedOnlyWhenItIsAboutThisTurn() = runTest {
        server.enqueue(json(202, """{"data":{"turnId":"$key","state":"pending"}}"""))
        server.enqueue(error(400, "invalid_request"))
        server.enqueue(error(404, "not_found"))
        server.enqueue(error(409, "idempotency_conflict"))
        server.enqueue(error(409, "request_in_progress"))
        server.enqueue(error(429, "rate_limited"))
        server.enqueue(error(502, "bad_gateway"))
        server.enqueue(error(401, "unauthenticated"))
        assertEquals(PostResult.NoAnswer, api.postTurn(thread, turn, "q"))
        assertEquals(PostResult.Refused("invalid_request"), api.postTurn(thread, turn, "q"))
        assertEquals(PostResult.Refused("not_found"), api.postTurn(thread, turn, "q"))
        assertEquals(PostResult.Refused("idempotency_conflict"), api.postTurn(thread, turn, "q"))
        assertEquals(PostResult.NoAnswer, api.postTurn(thread, turn, "q"))
        assertEquals(PostResult.NoAnswer, api.postTurn(thread, turn, "q"))
        assertEquals(PostResult.NoAnswer, api.postTurn(thread, turn, "q"))
        assertEquals(PostResult.SessionExpired, api.postTurn(thread, turn, "q"))
        server.close()
        assertEquals(PostResult.NoAnswer, api.postTurn(thread, turn, "q"))
    }

    @Test
    fun readsTheDurableTurnExactlyAsTheWorkerShapesIt() = runTest {
        server.enqueue(
            json(
                200,
                """{"data":{"turnId":"$turn","threadId":"$thread","scope":{"kind":"source","id":"$concern"},"content":"Did I pay?","state":"complete","reason":"cloud_allowed","errorCode":null,
                |"answer":"Paid on Friday.","claims":[{"text":"Paid","basis":"evidence","citations":[{"chunkId":"c1","sourceId":"$concern","attachmentId":null,"page":null,"start":0,"end":4,"excerpt":"Paid"}]},
                |{"text":"Maybe automatic","basis":"hypothesis","citations":[]}],"gaps":["No receipt"],"proposals":[{"kind":"email.reply","targetId":"$concern","description":"Ask for a receipt"}],
                |"context":[{"sourceId":"$concern","privacy":"hybrid"}],"invalidated":false,"withheld":false,"createdAt":"2026-10-08T10:00:00.000Z","completedAt":"2026-10-08T10:00:20.000Z"},"requestId":"r"}""".trimMargin(),
            ),
        )
        val dto = (api.turn(turn) as ReadResult.Ok).value
        assertEquals("/api/v1/chat/turns/$turn", server.takeRequest().target)
        val parsed = dto.toServerTurn()
        assertEquals(ServerState.COMPLETE, parsed.state)
        assertEquals(ChatScope(ScopeKind.SOURCE, concern), parsed.scope)
        assertEquals(listOf(ClaimBasis.EVIDENCE, ClaimBasis.HYPOTHESIS), parsed.answer?.claims?.map { it.basis })
        assertEquals("Paid", parsed.answer?.claims?.first()?.citations?.single()?.excerpt)
        assertEquals("email.reply", parsed.answer?.proposals?.single()?.kind)
        assertEquals(listOf(ContextSource(concern, "hybrid")), parsed.answer?.context)
        assertEquals(20_000L, parsed.completedAtMs!! - parsed.createdAtMs)
    }

    @Test
    fun aTurnWithoutAnswerCarriesTheReasonAndTheFlags() = runTest {
        server.enqueue(
            json(
                200,
                """{"data":{"turnId":"$turn","threadId":"$thread","scope":{"kind":"all"},"content":"q","state":"pending","reason":"local_only","errorCode":null,"answer":null,"claims":[],"gaps":[],"proposals":[],
                |"context":null,"invalidated":false,"withheld":true,"createdAt":"2026-10-08T10:00:00.000Z","completedAt":null}}""".trimMargin(),
            ),
        )
        val parsed = (api.turn(turn) as ReadResult.Ok).value.toServerTurn()
        assertEquals("local_only", parsed.reason)
        assertNull(parsed.answer)
        assertTrue(parsed.withheld)
        assertNull(parsed.completedAtMs)
    }

    @Test
    fun readFailuresAreReducedToTheirFixedCodes() = runTest {
        server.enqueue(error(404, "not_found"))
        server.enqueue(error(401, "unauthenticated"))
        assertEquals(ReadResult.Unavailable("not_found"), api.turn(turn))
        assertEquals(ReadResult.SessionExpired, api.turn(turn))
        assertEquals(ReadResult.Unavailable("not_found"), api.turn("../auth/refresh"))
        assertEquals(ReadResult.Unavailable("not_found"), api.history("../x", null))
        assertEquals(ReadResult.Unavailable("not_found"), api.history(thread, "not an id"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun historyPagesBackFromAGivenTurnWithoutOtherParameters() = runTest {
        val body = """{"data":{"thread":{"id":"$thread","scope":{"kind":"all"},"createdAt":"2026-10-08T10:00:00.000Z"},"turns":[],"hasMore":true,"nextBefore":"$turn"}}"""
        server.enqueue(json(200, body))
        server.enqueue(json(200, body))
        val first = (api.history(thread, null) as ReadResult.Ok).value
        assertTrue(first.hasMore)
        assertEquals(turn, first.nextBefore)
        api.history(thread, turn)
        assertEquals("/api/v1/chat/threads/$thread/turns", server.takeRequest().target)
        assertEquals("/api/v1/chat/threads/$thread/turns?before=$turn", server.takeRequest().target)
    }

    @Test
    fun cancelUsesItsOwnKeyAndMapsTheWorkersRefusalsHonestly() = runTest {
        server.enqueue(json(200, """{"data":{"turnId":"$turn","state":"cancelled"}}"""))
        server.enqueue(error(409, "already_dispatched"))
        server.enqueue(error(409, "not_cancellable", """{"state":"complete"}"""))
        server.enqueue(error(404, "not_found"))
        server.enqueue(error(503, "unavailable"))
        server.enqueue(error(401, "unauthenticated"))
        server.enqueue(error(400, "invalid_request"))
        assertEquals(CancelResult.Cancelled, api.cancel(turn, key))
        val request = server.takeRequest()
        assertEquals("/api/v1/chat/turns/$turn/cancel", request.target)
        assertEquals(key, request.headers["idempotency-key"])
        assertEquals("{}", request.body?.utf8())
        assertEquals(CancelResult.AlreadyDispatched, api.cancel(turn, key))
        assertEquals(CancelResult.NotCancellable("complete"), api.cancel(turn, key))
        assertEquals(CancelResult.NotFound, api.cancel(turn, key))
        assertEquals(CancelResult.NoAnswer, api.cancel(turn, key))
        assertEquals(CancelResult.SessionExpired, api.cancel(turn, key))
        assertEquals(CancelResult.Refused("invalid_request"), api.cancel(turn, key))
        assertEquals(CancelResult.NotFound, api.cancel("../x", key))
    }
}
