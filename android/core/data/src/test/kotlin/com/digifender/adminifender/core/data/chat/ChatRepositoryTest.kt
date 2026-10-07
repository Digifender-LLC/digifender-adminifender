package com.digifender.adminifender.core.data.chat

import com.digifender.adminifender.core.data.network.ReadResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val THREAD = "11111111-1111-4111-8111-111111111111"
private const val TURN = "22222222-2222-4222-8222-222222222222"
private const val SOURCE = "33333333-3333-4333-8333-333333333333"
private const val KEY = "44444444-4444-4444-8444-444444444444"
private val scope = ChatScope()
private val thread = ChatThread(THREAD, scope, "2026-10-07T10:00:00Z")
private fun savedTurn(state: String = "complete") = ChatTurn(TURN, THREAD, scope, "Synthetic invoice due?", state, answer = "The synthetic invoice is due Friday.", createdAt = thread.createdAt)

private class MemoryChatStore : ChatStore {
    val records = mutableMapOf<String, ChatSession>()
    override suspend fun get(scope: ChatScope) = records[scope.key]
    override suspend fun put(session: ChatSession) { records[session.scope.key] = session }
    override suspend fun clear() = records.clear()
}

private class FakeChatApi : ChatApi {
    var read: ReadResult<ChatTurn> = ReadResult.Unavailable("not_found")
    var history: ReadResult<ChatHistory> = ReadResult.Ok(ChatHistory(thread, emptyList(), false))
    var write: ChatWriteResult<ChatAccepted> = ChatWriteResult.NoAnswer
    var create: ChatWriteResult<ChatThread> = ChatWriteResult.Ok(thread)
    val submissions = mutableListOf<List<String>>()
    val creations = mutableListOf<Pair<ChatScope, String>>()
    var cancellations = 0
    var cancelResult: ChatWriteResult<ChatAccepted> = ChatWriteResult.Ok(ChatAccepted(TURN, "cancelled"))
    var onSubmit: suspend () -> Unit = {}
    var onCancel: suspend () -> Unit = {}
    var before: String? = null
    override suspend fun createThread(scope: ChatScope, key: String): ChatWriteResult<ChatThread> { creations += scope to key; return create }
    override suspend fun submitTurn(threadId: String, turnId: String, content: String, key: String): ChatWriteResult<ChatAccepted> {
        submissions += listOf(threadId, turnId, content, key)
        onSubmit()
        return write
    }
    override suspend fun turn(turnId: String) = read
    override suspend fun history(threadId: String, before: String?): ReadResult<ChatHistory> { this.before = before; return history }
    override suspend fun cancel(turnId: String, key: String): ChatWriteResult<ChatAccepted> { cancellations++; onCancel(); return cancelResult }
}

class ChatRepositoryTest {
    private val api = FakeChatApi()
    private val store = MemoryChatStore()
    private val ids = ArrayDeque(listOf(KEY, TURN))
    private fun repository() = ChatRepository(api, store) { ids.removeFirst() }

    @Test
    fun cancellationKeyMatchesBackendV4ValidatorAndIsStablePerTurn() {
        val key = chatCancellationKey(TURN)
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(key))
        assertEquals(key, chatCancellationKey(TURN))
        assertNotEquals(TURN, key)
        assertNotEquals(key, chatCancellationKey(THREAD))
    }

    @Test
    fun savesExactIdentityAndBodyBeforeSubmittingAndSurvivesRepositoryRecreation() = runTest {
        val repo = repository()
        repo.saveDraft(scope, "Synthetic invoice due?")
        api.onSubmit = {
            val saved = store.get(scope)!!
            assertEquals(TURN, saved.requestId)
            assertEquals("Synthetic invoice due?", saved.requestContent)
            assertEquals("", saved.draft)
        }
        val sent = repo.send(scope, "Synthetic invoice due?")
        assertTrue(sent.requestMissing)
        assertEquals(listOf(THREAD, TURN, "Synthetic invoice due?", TURN), api.submissions.single())
        api.read = ReadResult.Ok(savedTurn())
        val recovered = repository().load(scope)
        assertEquals(savedTurn(), recovered.turns.single())
        assertNull(recovered.session.requestId)
        assertEquals(1, api.submissions.size)
    }

    @Test
    fun explicitRetryProvesAbsenceAndUsesOriginalBodyEvenIfDraftChanged() = runTest {
        val repo = repository()
        repo.send(scope, "Synthetic invoice due?")
        repo.saveDraft(scope, "Changed draft")
        repo.retryMissing(scope)
        assertEquals(2, api.submissions.size)
        assertEquals(api.submissions[0], api.submissions[1])
        assertEquals("Changed draft", store.get(scope)!!.draft)
    }

    @Test
    fun timeoutSessionLossAndUncertainResultNeverAuthorizeReplay() = runTest {
        val repo = repository()
        repo.send(scope, "Synthetic invoice due?")
        api.read = ReadResult.Unavailable("network")
        repo.retryMissing(scope)
        api.read = ReadResult.SessionExpired
        repo.retryMissing(scope)
        api.read = ReadResult.Ok(savedTurn("uncertain"))
        val uncertain = repo.retryMissing(scope)
        assertEquals(1, api.submissions.size)
        assertEquals("uncertain", uncertain.turns.single().state)
        assertNull(uncertain.turns.single().answer)
        assertNull(uncertain.session.requestId)
    }

    @Test
    fun anAcknowledgedTurnIsNeverResubmittedEvenIfLaterReadSaysMissing() = runTest {
        api.write = ChatWriteResult.Ok(ChatAccepted(TURN, "pending"))
        val repo = repository()
        assertFalse(repo.send(scope, "Synthetic invoice due?").requestMissing)
        repo.retryMissing(scope)
        assertEquals(1, api.submissions.size)
    }

    @Test
    fun lostThreadCreationRetriesItsSavedKeyWithoutGeneratingTurns() = runTest {
        val repo = repository()
        api.create = ChatWriteResult.NoAnswer
        repo.send(scope, "Synthetic invoice due?")
        assertTrue(api.submissions.isEmpty())
        api.create = ChatWriteResult.Ok(thread)
        repo.send(scope, "Synthetic invoice due?")
        assertEquals(api.creations[0], api.creations[1])
        assertEquals(1, api.submissions.size)
    }

    @Test
    fun definiteRejectionRestoresDraftWithoutRetainingAnUnsendableRequest() = runTest {
        api.write = ChatWriteResult.Rejected("invalid_request")
        val result = repository().send(scope, "Synthetic invoice due?")
        assertNull(result.session.requestId)
        assertEquals("Synthetic invoice due?", result.session.draft)
        assertEquals("invalid_request", result.message)
    }

    @Test
    fun inProgressAndUnknownRefusalsKeepOriginalIdentityUntilReadback() = runTest {
        val repo = repository()
        api.write = ChatWriteResult.Rejected("request_in_progress")
        val sent = repo.send(scope, "Synthetic invoice due?")
        assertEquals(TURN, sent.session.requestId)
        assertEquals("", sent.session.draft)
        api.write = ChatWriteResult.Rejected("unreadable_error")
        assertEquals(TURN, repo.retryMissing(scope).session.requestId)
        api.read = ReadResult.Ok(savedTurn("running"))
        val recovered = repo.retryMissing(scope)
        assertEquals(TURN, recovered.session.requestId)
        assertTrue(recovered.session.requestKnown)
        assertEquals("running", recovered.turns.single().state)
        assertEquals(2, api.submissions.size)
        assertEquals(api.submissions.first(), api.submissions.last())
    }

    @Test
    fun duplicateSendWhileRequestIsUnconfirmedDoesNotAllocateAnotherIdentity() = runTest {
        val repo = repository()
        repo.send(scope, "Synthetic invoice due?")
        repo.send(scope, "Another question")
        assertEquals(1, api.submissions.size)
    }

    @Test
    fun cancellingRunningOrUncertainWorkNeverClaimsItWasCancelled() = runTest {
        store.put(ChatSession(scope, KEY, THREAD))
        val repo = repository()
        for (state in listOf("running", "uncertain")) {
            api.read = ReadResult.Ok(savedTurn(state))
            assertEquals("already_dispatched", repo.cancel(scope, TURN).message)
        }
        assertEquals(0, api.cancellations)
    }

    @Test
    fun cancellationIsConfirmedByReadingTheDurableCancelledState() = runTest {
        store.put(ChatSession(scope, KEY, THREAD, requestId = TURN, requestContent = savedTurn().content, requestKnown = true))
        api.read = ReadResult.Ok(savedTurn("pending"))
        api.onCancel = { api.read = ReadResult.Ok(savedTurn("cancelled")) }
        val result = repository().cancel(scope, TURN)
        assertEquals(1, api.cancellations)
        assertEquals("cancelled", result.turns.single().state)
        assertNull(result.session.requestId)
    }

    @Test
    fun aCancellationRaceShowsDispatchedStateWithoutClaimingCancellation() = runTest {
        store.put(ChatSession(scope, KEY, THREAD, requestId = TURN, requestContent = savedTurn().content, requestKnown = true))
        api.read = ReadResult.Ok(savedTurn("pending"))
        api.onCancel = { api.read = ReadResult.Ok(savedTurn("running")) }
        api.cancelResult = ChatWriteResult.Rejected("already_dispatched")
        val result = repository().cancel(scope, TURN)
        assertEquals("already_dispatched", result.message)
        assertEquals("running", result.turns.single().state)
        assertEquals(TURN, result.session.requestId)
    }

    @Test
    fun lostCancellationResponseDoesNotReportSuccessWhileServerStillWaits() = runTest {
        store.put(ChatSession(scope, KEY, THREAD, requestId = TURN, requestContent = savedTurn().content, requestKnown = true))
        api.read = ReadResult.Ok(savedTurn("pending"))
        api.cancelResult = ChatWriteResult.NoAnswer
        val result = repository().cancel(scope, TURN)
        assertEquals("pending", result.turns.single().state)
        assertEquals("request_not_confirmed", result.message)
    }

    @Test
    fun deletionAndWithholdingRemoveAllGeneratedContentEvenIfTheWireContainsIt() = runTest {
        store.put(ChatSession(scope, KEY, THREAD))
        val repo = repository()
        val turn = savedTurn().copy(claims = listOf(ChatClaim("Synthetic claim", "evidence", emptyList())), gaps = listOf("gap"), proposals = listOf(ChatProposal("email.reply", SOURCE, "reply")))
        for (hidden in listOf(turn.copy(invalidated = true), turn.copy(withheld = true))) {
            api.history = ReadResult.Ok(ChatHistory(thread, listOf(hidden), false))
            val shown = repo.load(scope).turns.single()
            assertNull(shown.answer)
            assertTrue(shown.claims.isEmpty())
            assertTrue(shown.gaps.isEmpty())
            assertTrue(shown.proposals.isEmpty())
        }
    }

    @Test
    fun citationsAreReauthorizedAtOpeningAndNeverFallBackToTheOldExcerpt() = runTest {
        store.put(ChatSession(scope, KEY, THREAD))
        val repo = repository()
        val citation = ChatCitation("$SOURCE:body-v1:0", SOURCE, start = 0, end = 7, excerpt = "Invoice")
        val cited = savedTurn().copy(claims = listOf(ChatClaim("Invoice", "evidence", listOf(citation))))
        api.read = ReadResult.Ok(cited)
        assertEquals(ChatCitationResult.Available(citation), repo.citation(scope, TURN, citation))
        api.read = ReadResult.Ok(cited.copy(withheld = true))
        assertEquals(ChatCitationResult.Unavailable, repo.citation(scope, TURN, citation))
        api.read = ReadResult.Unavailable("network")
        assertEquals(ChatCitationResult.Unavailable, repo.citation(scope, TURN, citation))
        api.read = ReadResult.Ok(cited.copy(claims = listOf(ChatClaim("Invoice", "evidence", listOf(citation.copy(excerpt = "short"))))))
        assertEquals(ChatCitationResult.Unavailable, repo.citation(scope, TURN, citation))
    }

    @Test
    fun wrongScopeOrThreadNeverLeaksIntoHistory() = runTest {
        store.put(ChatSession(scope, KEY, THREAD))
        api.history = ReadResult.Ok(ChatHistory(thread, listOf(savedTurn().copy(scope = ChatScope("source", SOURCE))), false))
        assertEquals("malformed_response", repository().load(scope).message)
        assertTrue(repository().load(scope).turns.isEmpty())
    }

    @Test
    fun duplicateHistoryIdsAreRejectedBeforeTheyCanBecomeComposeKeys() = runTest {
        store.put(ChatSession(scope, KEY, THREAD))
        api.history = ReadResult.Ok(ChatHistory(thread, listOf(savedTurn(), savedTurn()), false))
        val result = repository().load(scope)
        assertEquals("malformed_response", result.message)
        assertTrue(result.turns.isEmpty())
    }

    @Test
    fun pagingPreservesServerOrderAndTheBeforeAnchor() = runTest {
        store.put(ChatSession(scope, KEY, THREAD))
        api.history = ReadResult.Ok(ChatHistory(thread, listOf(savedTurn()), true))
        val loaded = repository().load(scope, TURN)
        assertEquals(TURN, api.before)
        assertEquals(listOf(savedTurn()), loaded.turns)
        assertTrue(loaded.hasMore)
    }

    @Test
    fun signOutClearsDraftsAndRequestIdentitiesForEveryScope() = runTest {
        val repo = repository()
        repo.saveDraft(scope, "Synthetic draft")
        store.put(ChatSession(ChatScope("source", SOURCE), KEY, THREAD, "Other draft", TURN, "Question"))
        repo.clear()
        assertTrue(store.records.isEmpty())
    }
}
