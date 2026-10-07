package com.digifender.adminifender.ui

import com.digifender.adminifender.R
import com.digifender.adminifender.core.data.chat.ChatAccepted
import com.digifender.adminifender.core.data.chat.ChatCitation
import com.digifender.adminifender.core.data.chat.ChatApi
import com.digifender.adminifender.core.data.chat.ChatHistory
import com.digifender.adminifender.core.data.chat.ChatRepository
import com.digifender.adminifender.core.data.chat.ChatScope
import com.digifender.adminifender.core.data.chat.ChatSession
import com.digifender.adminifender.core.data.chat.ChatStore
import com.digifender.adminifender.core.data.chat.ChatThread
import com.digifender.adminifender.core.data.chat.ChatTurn
import com.digifender.adminifender.core.data.chat.ChatWriteResult
import com.digifender.adminifender.core.data.network.ReadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val CHAT_THREAD = "11111111-1111-4111-8111-111111111111"
private const val CHAT_TURN = "22222222-2222-4222-8222-222222222222"
private val chatScope = ChatScope()

private class UiChatStore : ChatStore {
    var session: ChatSession? = ChatSession(chatScope, "key", CHAT_THREAD)
    override suspend fun get(scope: ChatScope) = session
    override suspend fun put(session: ChatSession) { this.session = session }
    override suspend fun clear() { session = null }
}

private class UiChatApi : ChatApi {
    var state = "pending"
    var invalidated = false
    var reads = 0
    var sends = 0
    var gate: CompletableDeferred<Unit>? = null
    var turnGate: CompletableDeferred<Unit>? = null
    private val thread = ChatThread(CHAT_THREAD, chatScope, "2026-10-07T10:00:00Z")
    fun turn() = ChatTurn(CHAT_TURN, CHAT_THREAD, chatScope, "Synthetic question", state, answer = "Synthetic answer", invalidated = invalidated, createdAt = thread.createdAt)
    override suspend fun createThread(scope: ChatScope, key: String) = ChatWriteResult.Ok(thread)
    override suspend fun submitTurn(threadId: String, turnId: String, content: String, key: String): ChatWriteResult<ChatAccepted> { sends++; return ChatWriteResult.NoAnswer }
    override suspend fun turn(turnId: String): ReadResult<ChatTurn> {
        val snapshot = turn()
        turnGate?.await()
        return ReadResult.Ok(snapshot)
    }
    override suspend fun history(threadId: String, before: String?): ReadResult<ChatHistory> {
        reads++
        val snapshot = turn()
        gate?.await()
        return ReadResult.Ok(ChatHistory(thread, listOf(snapshot), false))
    }
    override suspend fun cancel(turnId: String, key: String): ChatWriteResult<ChatAccepted> = ChatWriteResult.Rejected("already_dispatched")
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val api = UiChatApi()
    private val store = UiChatStore()
    private val repo = ChatRepository(api, store)

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    @Test
    fun pollingUsesBoundedBackoffThenWaitsForForegroundOrRefresh() = runTest {
        val model = ChatViewModel(chatScope, repo)
        model.onForeground()
        assertEquals(1, api.reads)
        for ((delay, expected) in listOf(1_000L to 2, 2_000L to 3, 5_000L to 4, 10_000L to 5)) {
            advanceTimeBy(delay)
            runCurrent()
            assertEquals(expected, api.reads)
        }
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(5, api.reads)
        model.refresh()
        assertEquals(6, api.reads)
        model.onBackground()
    }

    @Test
    fun backgroundStopsPollingClearsAnswersAndForegroundRechecksDeletion() = runTest {
        api.state = "complete"
        val model = ChatViewModel(chatScope, repo)
        model.onForeground()
        assertEquals("Synthetic answer", model.state.value.snapshot!!.turns.single().answer)
        model.onBackground()
        assertNull(model.state.value.snapshot)
        advanceTimeBy(60_000)
        assertEquals(1, api.reads)
        api.invalidated = true
        model.onForeground()
        assertNull(model.state.value.snapshot!!.turns.single().answer)
        assertEquals(R.string.chat_invalidated, chatTurnLabel(model.state.value.snapshot!!.turns.single()))
        model.onBackground()
    }

    @Test
    fun uncertainResultIsVisibleAndHasNoAutomaticRetryOrPoll() = runTest {
        api.state = "uncertain"
        val model = ChatViewModel(chatScope, repo)
        model.onForeground()
        val shown = model.state.value.snapshot!!.turns.single()
        assertNull(shown.answer)
        assertEquals(R.string.chat_uncertain, chatTurnLabel(shown))
        advanceTimeBy(60_000)
        assertEquals(1, api.reads)
        assertEquals(0, api.sends)
        model.onBackground()
    }

    @Test
    fun draftsArePersistedAndWaitingTurnsDisableSending() = runTest {
        val model = ChatViewModel(chatScope, repo)
        model.onForeground()
        model.editDraft("Synthetic draft")
        assertEquals("Synthetic draft", store.session!!.draft)
        assertFalse(model.state.value.canSend)
        model.send()
        assertEquals(0, api.sends)
        api.state = "complete"
        model.refresh()
        assertTrue(model.state.value.canSend)
        model.onBackground()
    }

    @Test
    fun syncDuringAnInflightReadSuppressesItsOldAnswerAndReadsAgain() = runTest {
        api.state = "complete"
        api.gate = CompletableDeferred()
        val model = ChatViewModel(chatScope, repo)
        model.onForeground()
        assertEquals(1, api.reads)
        api.invalidated = true
        model.refresh()
        api.gate!!.complete(Unit)
        runCurrent()
        assertEquals(2, api.reads)
        assertTrue(model.state.value.snapshot!!.turns.single().invalidated)
        assertNull(model.state.value.snapshot!!.turns.single().answer)
        model.onBackground()
    }

    @Test
    fun citationStopsInflightPollingAndSyncKeepsItsOldAnswerHidden() = runTest {
        val model = ChatViewModel(chatScope, repo)
        model.onForeground()
        api.state = "complete"
        api.gate = CompletableDeferred()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, api.reads)
        api.turnGate = CompletableDeferred()
        model.openCitation(CHAT_TURN, ChatCitation("synthetic:body:0", CHAT_THREAD, start = 0, end = 7, excerpt = "Invoice"))
        api.invalidated = true
        model.refresh()
        api.gate!!.complete(Unit)
        runCurrent()
        assertNull(model.state.value.snapshot)
        api.turnGate!!.complete(Unit)
        runCurrent()
        assertTrue(model.state.value.snapshot!!.turns.single().invalidated)
        assertNull(model.state.value.snapshot!!.turns.single().answer)
        model.onBackground()
    }

    @Test
    fun unknownClaimBasisIsNeverLabelledEvidence() {
        assertEquals(R.string.chat_basis_inference, claimLabel("invented certainty"))
        assertEquals(R.string.chat_basis_hypothesis, claimLabel("hypothesis"))
        assertEquals(R.string.chat_basis_report, claimLabel("report"))
    }
}
