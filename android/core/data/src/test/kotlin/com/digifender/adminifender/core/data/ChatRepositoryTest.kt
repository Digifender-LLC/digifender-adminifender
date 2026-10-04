package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.CallResult
import com.digifender.adminifender.core.data.network.ChatApi
import com.digifender.adminifender.core.data.network.ChatThreadDto
import com.digifender.adminifender.core.data.network.CitationDto
import com.digifender.adminifender.core.data.network.ClaimDto
import com.digifender.adminifender.core.data.network.ReadResult
import com.digifender.adminifender.core.data.network.ScopeDto
import com.digifender.adminifender.core.data.network.TurnDto
import com.digifender.adminifender.core.data.network.TurnStateDto
import com.digifender.adminifender.core.data.repository.AskResult
import com.digifender.adminifender.core.data.repository.ChatCache
import com.digifender.adminifender.core.data.repository.ChatRepository
import com.digifender.adminifender.core.data.repository.ChatScope
import com.digifender.adminifender.core.data.repository.ChatTurn
import com.digifender.adminifender.core.data.repository.ThreadResult
import com.digifender.adminifender.core.data.repository.TurnState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_800_000_000_000L
private const val SOURCE = "0b4e6f8a-2c3d-4e5f-8a9b-1c2d3e4f5a6b"

/** Scripted chat API: queued answers per endpoint and a log of calls. */
class FakeChatApi : ChatApi {
    val threads = ArrayDeque<CallResult<ChatThreadDto>>()
    val posts = ArrayDeque<CallResult<TurnStateDto>>()
    val turns = ArrayDeque<ReadResult<TurnDto>>()
    val cancels = ArrayDeque<CallResult<TurnStateDto>>()
    val log = mutableListOf<String>()

    override suspend fun createThread(idempotencyKey: String, scope: ScopeDto) = threads.removeFirst().also { log += "thread:$idempotencyKey:${scope.kind}" }

    override suspend fun postTurn(threadId: String, turnId: String, content: String) = posts.removeFirst().also { log += "post:$turnId" }

    override suspend fun turn(turnId: String) = (turns.removeFirstOrNull() ?: ReadResult.Unavailable("network")).also { log += "turn:$turnId" }

    override suspend fun cancelTurn(turnId: String, idempotencyKey: String) = cancels.removeFirst().also { log += "cancel:$turnId" }

    override suspend fun resumeExtraction(attachmentId: String, idempotencyKey: String): CallResult<Unit> = CallResult.Ok(Unit).also { log += "resume:$attachmentId" }
}

class InMemoryChatCache : ChatCache {
    val threads = mutableMapOf<ChatScope, String>()
    val turns = MutableStateFlow<Map<String, ChatTurn>>(emptyMap())

    override suspend fun thread(scope: ChatScope) = threads[scope]

    override suspend fun putThread(id: String, scope: ChatScope, createdAtMs: Long) {
        threads[scope] = id
    }

    override fun turns(threadId: String) = turns.map { m -> m.values.filter { it.threadId == threadId }.sortedBy { it.createdAtMs } }

    override suspend fun turn(id: String) = turns.value[id]

    override suspend fun insert(turn: ChatTurn) {
        check(turn.turnId !in turns.value) { "turn ID reused" }
        turns.value = turns.value + (turn.turnId to turn)
    }

    override suspend fun put(turn: ChatTurn) {
        turns.value = turns.value + (turn.turnId to turn)
    }

    override suspend fun unfinished() = turns.value.values.filter { !it.state.finished }
}

class ChatRepositoryTest {
    private val api = FakeChatApi()
    private val cache = InMemoryChatCache()
    private var ids = 0
    private val slept = mutableListOf<Long>()
    private val repo = ChatRepository(cache, api, { NOW }, { "00000000-0000-4000-8000-00000000000${ids++}" }, { slept += it })

    private fun turnDto(id: String, state: String, answer: String? = null) = TurnDto(
        id, "thread", "When is it due?", state, answer = answer,
        claims = if (answer == null) emptyList() else listOf(ClaimDto("Due Friday", "evidence", listOf(CitationDto("c0", SOURCE, start = 0, end = 3, excerpt = "Fri")))),
        createdAt = "2026-10-03T12:00:00Z",
    )

    @Test
    fun aLostThreadCreationIsRetriedWithTheSameKey() = runTest {
        api.threads += CallResult.NoAnswer
        api.threads += CallResult.Ok(ChatThreadDto("thread", ScopeDto("all"), "2026-10-03T12:00:00Z"))
        assertEquals(ThreadResult.NoAnswer, repo.openThread(ChatScope.ALL))
        assertEquals(ThreadResult.Ready("thread"), repo.openThread(ChatScope.ALL))
        assertEquals(api.log[0], api.log[1])
        // Cached afterwards: no third request.
        assertEquals(ThreadResult.Ready("thread"), repo.openThread(ChatScope.ALL))
        assertEquals(2, api.log.size)
    }

    @Test
    fun anAnswerIsFollowedWithBackoffAndStoredWhenComplete() = runTest {
        api.posts += CallResult.Ok(TurnStateDto("t", "pending"))
        api.turns += ReadResult.Ok(turnDto("00000000-0000-4000-8000-000000000000", "running"))
        api.turns += ReadResult.Ok(turnDto("00000000-0000-4000-8000-000000000000", "complete", "Friday"))
        val result = repo.ask("thread", "  When is it due?  ") as AskResult.Accepted
        val turn = cache.turn(result.turnId)!!
        assertEquals(TurnState.COMPLETE, turn.state)
        assertEquals("When is it due?", turn.content)
        assertEquals("Friday", turn.answer)
        assertEquals(SOURCE, turn.claims.single().citations.single().sourceId)
        assertEquals(listOf(1_000L, 2_000L), slept)
    }

    @Test
    fun anUnansweredQuestionStaysUnsentAndIsResentOnlyWithItsOwnId() = runTest {
        api.posts += CallResult.NoAnswer
        val id = (repo.ask("thread", "Q") as AskResult.Accepted).turnId
        var turn = cache.turn(id)!!
        assertEquals(TurnState.SENDING, turn.state)
        assertFalse(turn.sent)
        assertEquals(ChatRepository.NO_ANSWER, turn.errorCode)

        // A background refresh asks about it but never resends it.
        repo.refreshUnfinished()
        assertEquals(listOf("post:$id", "turn:$id"), api.log)

        api.posts += CallResult.Ok(TurnStateDto(id, "pending"))
        repo.send(id)
        assertEquals("post:$id", api.log[2])
        turn = cache.turn(id)!!
        assertTrue(turn.sent)
    }

    @Test
    fun aQuestionTheServerAlreadyHoldsIsRecoveredByReadingIt() = runTest {
        api.posts += CallResult.NoAnswer
        val id = (repo.ask("thread", "Q") as AskResult.Accepted).turnId
        api.turns += ReadResult.Ok(turnDto(id, "complete", "Friday"))
        repo.refreshUnfinished()
        val turn = cache.turn(id)!!
        assertTrue(turn.sent)
        assertEquals("Friday", turn.answer)
        assertEquals(1, api.log.count { it.startsWith("post:") })
    }

    @Test
    fun emptyAndOversizedQuestionsAreNotSent() = runTest {
        assertEquals(AskResult.Empty, repo.ask("thread", "   "))
        assertEquals(AskResult.TooLong, repo.ask("thread", "x".repeat(ChatRepository.MAX_QUESTION_CHARS + 1)))
        assertTrue(api.log.isEmpty())
    }

    @Test
    fun aDispatchedQuestionCannotBeCancelledAndSaysSo() = runTest {
        api.posts += CallResult.Ok(TurnStateDto("t", "running"))
        repeat(4) { api.turns += ReadResult.Ok(turnDto("00000000-0000-4000-8000-000000000000", "running")) }
        val id = (repo.ask("thread", "Q") as AskResult.Accepted).turnId
        api.cancels += CallResult.Refused("already_dispatched")
        api.turns += ReadResult.Ok(turnDto(id, "running"))
        assertEquals(CallResult.Refused("already_dispatched"), repo.cancel(id))
        assertEquals(TurnState.RUNNING, cache.turn(id)!!.state)
    }

    @Test
    fun aWithheldAnswerIsNotShown() = runTest {
        api.posts += CallResult.Ok(TurnStateDto("t", "pending"))
        api.turns += ReadResult.Ok(turnDto("00000000-0000-4000-8000-000000000000", "complete").copy(withheld = true))
        val id = (repo.ask("thread", "Q") as AskResult.Accepted).turnId
        val turn = cache.turn(id)!!
        assertTrue(turn.withheld)
        assertNull(turn.answer)
    }
}
