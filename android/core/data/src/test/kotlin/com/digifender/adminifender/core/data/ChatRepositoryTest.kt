package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.CancelResult
import com.digifender.adminifender.core.data.network.ChatScope
import com.digifender.adminifender.core.data.network.ContextDto
import com.digifender.adminifender.core.data.network.HistoryDto
import com.digifender.adminifender.core.data.network.PostResult
import com.digifender.adminifender.core.data.network.ReadResult
import com.digifender.adminifender.core.data.network.ScopeDto
import com.digifender.adminifender.core.data.network.ScopeKind
import com.digifender.adminifender.core.data.network.ThreadDto
import com.digifender.adminifender.core.data.network.ThreadResult
import com.digifender.adminifender.core.data.repository.CancelNote
import com.digifender.adminifender.core.data.repository.CancelOutcome
import com.digifender.adminifender.core.data.repository.ChatRepository
import com.digifender.adminifender.core.data.repository.CreateState
import com.digifender.adminifender.core.data.repository.Dispatch
import com.digifender.adminifender.core.data.repository.EditAgain
import com.digifender.adminifender.core.data.repository.LocalInvalid
import com.digifender.adminifender.core.data.repository.ReadOutcome
import com.digifender.adminifender.core.data.repository.SendOutcome
import com.digifender.adminifender.core.data.repository.ServerState
import com.digifender.adminifender.core.data.repository.Settle
import com.digifender.adminifender.core.data.repository.TurnPhase
import com.digifender.adminifender.core.data.repository.phase
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val NOW = Instant.parse("2026-10-08T10:00:00Z").toEpochMilli()
private const val CONCERN = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepositoryTest {
    private val api = FakeChatApi()
    private val cache = InMemoryChatCache()
    private var ids = 0
    private fun newId() = "00000000-0000-4000-8000-%012d".format(++ids)
    private val repo = ChatRepository(api, cache, { NOW }) { newId() }

    private suspend fun thread(scope: ChatScope = ChatScope(ScopeKind.ALL)) = cache.threadOnce(repo.openThread(scope)!!)!!

    private suspend fun ask(localId: String, text: String = "Did I pay?") = (repo.submit(localId, text) as SendOutcome.Submitted).turnId

    /** A question that the Worker accepted as pending, on a conversation the Worker has. */
    private suspend fun accepted(): Pair<String, String> {
        val t = thread()
        api.threadResults += created()
        api.postResults += PostResult.Accepted("pending")
        val id = ask(t.localId)
        assertEquals(Settle.DONE, repo.dispatch(id))
        return t.localId to id
    }

    @Test
    fun submitStoresTheUuidTheExactTextAndTheScopeAndSendsNothing() = runTest {
        val t = thread(ChatScope(ScopeKind.CONCERN, CONCERN))
        repo.setDraft(t.localId, "draft")
        val id = ask(t.localId, "  Did I pay?\n")
        val turn = cache.turnOnce(id)!!
        assertEquals("  Did I pay?\n", turn.content)
        assertEquals(ChatScope(ScopeKind.CONCERN, CONCERN), turn.scope)
        assertEquals(Dispatch.QUEUED, turn.dispatch)
        assertEquals("", cache.threadOnce(t.localId)!!.draft)
        assertTrue(api.log.isEmpty())
        assertEquals(TurnPhase.NOT_SENT, turn.phase())
    }

    @Test
    fun blankAndOverlongQuestionsAreNotStored() = runTest {
        val t = thread()
        assertEquals(SendOutcome.Empty, repo.submit(t.localId, " \n "))
        assertEquals(SendOutcome.TooLong, repo.submit(t.localId, "x".repeat(4001)))
        assertTrue(repo.submit(t.localId, "x".repeat(4000)) is SendOutcome.Submitted)
        assertEquals(SendOutcome.Gone, repo.submit("missing", "hello"))
        assertEquals(1, cache.turnsOnce(t.localId).size)
    }

    @Test
    fun theSameSubjectReusesItsConversationAndAnotherSubjectDoesNot() = runTest {
        val a = repo.openThread(ChatScope(ScopeKind.ALL))
        assertEquals(a, repo.openThread(ChatScope(ScopeKind.ALL)))
        assertNotEquals(a, repo.openThread(ChatScope(ScopeKind.CONCERN, CONCERN)))
        assertNull(repo.openThread(ChatScope(ScopeKind.CONCERN, null)))
        assertNull(repo.openThread(ChatScope(ScopeKind.ALL, CONCERN)))
    }

    @Test
    fun theAttemptIsStoredBeforeThePostAndThePostCarriesTheTurnId() = runTest {
        val t = thread()
        val id = ask(t.localId)
        var seenAtPost: Dispatch? = null
        api.onPost = { turnId -> seenAtPost = cache.peekTurn(turnId)?.dispatch }
        api.threadResults += created()
        api.postResults += PostResult.Accepted("pending")
        assertEquals(Settle.DONE, repo.dispatch(id))
        assertEquals(Dispatch.ATTEMPTED, seenAtPost)
        assertEquals(listOf(Triple(SERVER_THREAD, id, "Did I pay?")), api.posted)
        assertEquals(listOf(t.createKey), api.threadKeys)
        val turn = cache.turnOnce(id)!!
        assertEquals(Dispatch.ACCEPTED, turn.dispatch)
        assertEquals(ServerState.PENDING, turn.state)
        assertEquals(CreateState.CREATED, cache.threadOnce(t.localId)!!.createState)
    }

    @Test
    fun anUnconfirmedThreadCreationIsRepeatedWithTheSameKeyAndTheQuestionStaysQueued() = runTest {
        val t = thread()
        val id = ask(t.localId)
        api.threadResults += ThreadResult.NoAnswer
        assertEquals(Settle.NOT_CONFIRMED, repo.dispatch(id))
        assertEquals(Dispatch.QUEUED, cache.turnOnce(id)!!.dispatch)
        assertEquals(CreateState.UNCONFIRMED, cache.threadOnce(t.localId)!!.createState)
        assertTrue(api.posted.isEmpty())

        api.threadResults += created()
        api.postResults += PostResult.Accepted("pending")
        assertEquals(Settle.DONE, repo.dispatch(id))
        assertEquals(listOf(t.createKey, t.createKey), api.threadKeys)
        assertEquals(1, api.posted.size)
    }

    @Test
    fun aRefusedConversationRefusesTheQuestionAndAnyLaterOne() = runTest {
        val t = thread()
        val id = ask(t.localId)
        api.threadResults += ThreadResult.Refused("scope_unavailable")
        assertEquals(Settle.DONE, repo.dispatch(id))
        val turn = cache.turnOnce(id)!!
        assertEquals(Dispatch.REFUSED, turn.dispatch)
        assertEquals("scope_unavailable", turn.dispatchError)
        assertTrue(api.posted.isEmpty())
        assertEquals(SendOutcome.ScopeRefused, repo.submit(t.localId, "again"))
        // A refused subject is not reused for a new conversation either.
        assertNotEquals(t.localId, repo.openThread(ChatScope(ScopeKind.ALL)))
    }

    @Test
    fun aThreadAnswerAboutAnotherScopeIsNotAdopted() = runTest {
        val t = thread(ChatScope(ScopeKind.CONCERN, CONCERN))
        val id = ask(t.localId)
        api.threadResults += created(scope = ScopeDto("all"))
        assertEquals(Settle.DONE, repo.dispatch(id))
        assertEquals(CreateState.REFUSED, cache.threadOnce(t.localId)!!.createState)
        assertNull(cache.threadOnce(t.localId)!!.serverId)
        assertTrue(api.posted.isEmpty())
    }

    @Test
    fun aLostPostIsReadBackAfterProcessDeathAndNeverSentAgainWhenTheWorkerHasIt() = runTest {
        val t = thread()
        val id = ask(t.localId)
        api.threadResults += created()
        api.postResults += PostResult.NoAnswer
        assertEquals(Settle.NOT_CONFIRMED, repo.dispatch(id))
        assertEquals(TurnPhase.UNCONFIRMED, cache.turnOnce(id)!!.phase())

        // The process died; a new repository starts over the same stored rows.
        val restarted = ChatRepository(api, cache, { NOW }) { newId() }
        api.turnReads += { ReadResult.Ok(turnDto(id, state = "running")) }
        val report = restarted.recover()
        assertTrue(report.waiting)
        assertEquals(1, api.posted.size)
        assertEquals(listOf("thread", "post", "turn"), api.log)
        val turn = cache.turnOnce(id)!!
        assertEquals(Dispatch.ACCEPTED, turn.dispatch)
        assertEquals(TurnPhase.RUNNING, turn.phase())
        assertEquals(1, cache.turnsOnce(t.localId).size)
    }

    @Test
    fun whenTheWorkerHasNoSuchTurnTheSameRequestIsSentAgainWithTheSameIdentity() = runTest {
        val t = thread()
        val id = ask(t.localId)
        api.threadResults += created()
        api.postResults += PostResult.NoAnswer
        repo.dispatch(id)
        api.turnReads += { ReadResult.Unavailable("not_found") }
        api.postResults += PostResult.Accepted("pending")
        assertEquals(Settle.DONE, repo.dispatch(id))
        assertEquals(listOf("thread", "post", "turn", "post"), api.log)
        assertEquals(api.posted[0], api.posted[1])
        assertEquals(1, cache.turnsOnce(t.localId).size)
        assertEquals(Dispatch.ACCEPTED, cache.turnOnce(id)!!.dispatch)
    }

    @Test
    fun anUnansweredReadBackChangesAndSendsNothing() = runTest {
        val t = thread()
        val id = ask(t.localId)
        api.threadResults += created()
        api.postResults += PostResult.NoAnswer
        repo.dispatch(id)
        api.turnReads += { ReadResult.Unavailable("network") }
        val report = repo.recover()
        assertTrue(report.unreachable)
        assertTrue(report.waiting)
        assertEquals(1, api.posted.size)
        assertEquals(Dispatch.ATTEMPTED, cache.turnOnce(id)!!.dispatch)

        api.turnReads += { ReadResult.SessionExpired }
        assertTrue(repo.recover().signIn)
        assertEquals(1, api.posted.size)
    }

    @Test
    fun aPostRefusedForGoodIsFinalAndTheTextComesBackToTheDraft() = runTest {
        val t = thread()
        val id = ask(t.localId, "Do not lose me")
        api.threadResults += created()
        api.postResults += PostResult.Refused("invalid_request")
        repo.dispatch(id)
        assertEquals(TurnPhase.REFUSED, cache.turnOnce(id)!!.phase())
        assertFalse(repo.recover().waiting)
        assertEquals(1, api.posted.size)

        assertEquals(EditAgain.Restored("Do not lose me"), repo.editAgain(id))
        assertNull(cache.turnOnce(id))
        assertEquals("Do not lose me", cache.threadOnce(t.localId)!!.draft)
    }

    @Test
    fun aSignedOutPostStaysAttemptedBecauseItMayHaveReachedTheWorker() = runTest {
        val t = thread()
        val id = ask(t.localId)
        api.threadResults += created()
        api.postResults += PostResult.SessionExpired
        assertEquals(Settle.SIGN_IN, repo.dispatch(id))
        assertEquals(Dispatch.ATTEMPTED, cache.turnOnce(id)!!.dispatch)
    }

    @Test
    fun aCompletedAnswerEntersTheCacheAndNeedsItsCitedEmailsOnThePhone() = runTest {
        val (_, id) = accepted()
        val answer = turnDto(id, state = "complete", answer = "Paid on Friday.", claims = listOf(claim(citation(SOURCE_1))), completedAt = "2026-10-08T10:01:00.000Z", reason = "cloud_allowed")
        api.turnReads += { ReadResult.Ok(answer) }
        assertEquals(ReadOutcome.Updated, repo.readTurn(id))
        val held = cache.turnOnce(id)!!
        assertNull(held.answer)
        assertEquals(LocalInvalid.UNVERIFIED, held.localInvalid)
        assertEquals(TurnPhase.UNVERIFIED, held.phase())

        cache.sources[SOURCE_1] = fact(SOURCE_1)
        api.turnReads += { ReadResult.Ok(answer) }
        repo.readTurn(id)
        val shown = cache.turnOnce(id)!!
        assertEquals("Paid on Friday.", shown.answer?.text)
        assertEquals("Paid in full", shown.answer?.claims?.single()?.citations?.single()?.excerpt)
        assertEquals(TurnPhase.COMPLETE, shown.phase())
    }

    @Test
    fun aFinishedTurnNeverGoesBackToWaiting() = runTest {
        val (_, id) = accepted()
        api.turnReads += { ReadResult.Ok(turnDto(id, state = "failed", errorCode = "invalid_output")) }
        repo.readTurn(id)
        api.turnReads += { ReadResult.Ok(turnDto(id, state = "pending")) }
        repo.readTurn(id)
        assertEquals(TurnPhase.FAILED, cache.turnOnce(id)!!.phase())
        assertEquals("invalid_output", cache.turnOnce(id)!!.errorCode)
    }

    @Test
    fun aPendingReasonFailureUncertainAndWithheldStaySeparateStates() = runTest {
        val (_, id) = accepted()
        api.turnReads += { ReadResult.Ok(turnDto(id, state = "pending", reason = "local_only")) }
        repo.readTurn(id)
        assertEquals(TurnPhase.WAITING, cache.turnOnce(id)!!.phase())
        assertEquals("local_only", cache.turnOnce(id)!!.reason)

        api.turnReads += { ReadResult.Ok(turnDto(id, state = "uncertain", errorCode = "dispatch_uncertain")) }
        repo.readTurn(id)
        assertEquals(TurnPhase.UNCERTAIN, cache.turnOnce(id)!!.phase())

        api.turnReads += { ReadResult.Ok(turnDto(id, state = "complete", withheld = true)) }
        repo.readTurn(id)
        assertEquals(TurnPhase.WITHHELD, cache.turnOnce(id)!!.phase())

        api.turnReads += { ReadResult.Ok(turnDto(id, state = "complete", invalidated = true)) }
        repo.readTurn(id)
        assertEquals(TurnPhase.INVALIDATED, cache.turnOnce(id)!!.phase())
    }

    @Test
    fun aSlowerOlderReadCannotReplaceANewerOne() = runTest {
        val (_, id) = accepted()
        val gate = CompletableDeferred<Unit>()
        api.turnReads += {
            gate.await()
            ReadResult.Ok(turnDto(id, state = "pending"))
        }
        val slow = async { repo.readTurn(id) }
        runCurrent()
        api.turnReads += { ReadResult.Ok(turnDto(id, state = "complete", answer = "Done.", completedAt = "2026-10-08T10:01:00.000Z")) }
        assertEquals(ReadOutcome.Updated, repo.readTurn(id))
        gate.complete(Unit)
        assertEquals(ReadOutcome.Stale, slow.await())
        assertEquals(ServerState.COMPLETE, cache.turnOnce(id)!!.state)
    }

    @Test
    fun aReadThatBeganBeforeSignOutWritesNothingAfterIt() = runTest {
        val (_, id) = accepted()
        val gate = CompletableDeferred<Unit>()
        api.turnReads += {
            gate.await()
            ReadResult.Ok(turnDto(id, state = "complete", answer = "Done."))
        }
        val late = async { repo.readTurn(id) }
        runCurrent()
        repo.invalidate()
        cache.clear()
        gate.complete(Unit)
        assertEquals(ReadOutcome.SignIn, late.await())
        assertNull(cache.turnOnce(id))
        assertTrue(cache.needingCheck().isEmpty())
    }

    @Test
    fun aLateHistoryPageCannotRecreateAClearedConversation() = runTest {
        val (localId, _) = accepted()
        val gate = CompletableDeferred<Unit>()
        api.historyReads += {
            gate.await()
            ReadResult.Ok(HistoryDto(ThreadDto(SERVER_THREAD, ScopeDto("all"), "2026-10-08T10:00:00.000Z"), listOf(turnDto("33333333-3333-4333-8333-333333333333", state = "complete")), false))
        }
        val late = async { repo.refreshHistory(localId) }
        runCurrent()
        cache.clear()
        gate.complete(Unit)
        assertEquals(ReadOutcome.NotFound, late.await())
        assertTrue(cache.turnsOnce(localId).isEmpty())
    }

    @Test
    fun historyIsOldestFirstLatestPageThenBeforeTheCursorTheWorkerGave() = runTest {
        val (localId, first) = accepted()
        val older = "00000000-0000-4000-8000-0000000000a1"
        val newer = "00000000-0000-4000-8000-0000000000a2"
        api.historyReads += {
            ReadResult.Ok(
                HistoryDto(
                    ThreadDto(SERVER_THREAD, ScopeDto("all"), "2026-10-08T10:00:00.000Z"),
                    listOf(turnDto(first, state = "complete", createdAt = "2026-10-08T10:00:00.000Z"), turnDto(newer, state = "complete", createdAt = "2026-10-08T10:05:00.000Z")),
                    true,
                    first,
                ),
            )
        }
        assertEquals(ReadOutcome.Updated, repo.refreshHistory(localId))
        assertEquals(first, cache.threadOnce(localId)!!.olderCursor)
        assertEquals(listOf(null), api.historyBefore)

        api.historyReads += {
            ReadResult.Ok(HistoryDto(ThreadDto(SERVER_THREAD, ScopeDto("all"), "2026-10-08T10:00:00.000Z"), listOf(turnDto(older, state = "complete", createdAt = "2026-10-08T09:00:00.000Z")), false))
        }
        assertEquals(ReadOutcome.Updated, repo.loadEarlier(localId))
        // Paged back from the cursor the Worker gave, and the cursor cleared by the page's own answer.
        assertEquals(listOf(null, first), api.historyBefore)
        assertFalse(cache.threadOnce(localId)!!.olderAvailable)
        assertEquals(listOf(older, first, newer), cache.turnsOnce(localId).map { it.turnId })
    }

    private fun serverId(index: Int) = "00000000-0000-4000-9000-%012d".format(index)

    /**
     * A Worker holding [order] (oldest first, in its own storage order). It answers a history request from the `before`
     * the phone sent last, with every turn stamped the same time, which is later than the phone's own clock.
     */
    private fun worker(order: List<String>): suspend () -> ReadResult<HistoryDto> = {
        val before = api.historyBefore.last()
        val end = if (before == null) order.size else order.indexOf(before)
        check(end >= 0) { "unknown cursor" }
        val start = maxOf(0, end - 50)
        val page = order.subList(start, end)
        ReadResult.Ok(
            HistoryDto(
                ThreadDto(SERVER_THREAD, ScopeDto("all"), "2026-10-08T10:00:00.000Z"),
                page.map { turnDto(it, state = "complete", createdAt = "2027-01-01T00:00:00.000Z") },
                start > 0,
                if (start > 0) page.first() else null,
            ),
        )
    }

    @Test
    fun earlierPagesFollowTheWorkersCursorEvenWhenThePhonesClockDisagreesAndEveryStoredTimeIsTheSame() = runTest {
        // The phone's own question was made with a clock far behind: by its rows it is the oldest turn, by the Worker's
        // order it is the newest of 130. Anchoring on it would ask for the newest page again, for ever.
        val (localId, mine) = accepted()
        val order = (0 until 129).map { serverId(it) } + mine
        repeat(3) { api.historyReads += worker(order) }

        assertEquals(ReadOutcome.Updated, repo.refreshHistory(localId))
        assertEquals(order[80], cache.threadOnce(localId)!!.olderCursor)
        assertEquals(ReadOutcome.Updated, repo.loadEarlier(localId))
        assertEquals(order[30], cache.threadOnce(localId)!!.olderCursor)
        assertEquals(ReadOutcome.Updated, repo.loadEarlier(localId))

        assertEquals(listOf(null, order[80], order[30]), api.historyBefore)
        val thread = cache.threadOnce(localId)!!
        assertNull(thread.olderCursor)
        assertFalse(thread.olderAvailable)
        assertEquals(130, cache.turnsOnce(localId).size)
        assertEquals(order.toSet(), cache.turnsOnce(localId).map { it.turnId }.toSet())
        // Nothing is left to ask for, so nothing is asked.
        assertEquals(ReadOutcome.NothingYet, repo.loadEarlier(localId))
        assertEquals(3, api.historyBefore.size)
    }

    @Test
    fun refreshingTheNewestPageNeverMovesTheCursorBackToANewerTurn() = runTest {
        val (localId, mine) = accepted()
        val order = (0 until 129).map { serverId(it) } + mine
        repeat(4) { api.historyReads += worker(order) }
        repo.refreshHistory(localId)
        repo.loadEarlier(localId)
        assertEquals(order[30], cache.threadOnce(localId)!!.olderCursor)

        assertEquals(ReadOutcome.Updated, repo.refreshHistory(localId))
        assertEquals(order[30], cache.threadOnce(localId)!!.olderCursor)
        // The next earlier page continues from where the earlier pages stopped.
        assertEquals(ReadOutcome.Updated, repo.loadEarlier(localId))
        assertEquals(listOf(null, order[80], null, order[30]), api.historyBefore)
        assertNull(cache.threadOnce(localId)!!.olderCursor)
    }

    private fun page(vararg ids: String, hasMore: Boolean, nextBefore: String?) =
        ReadResult.Ok(HistoryDto(ThreadDto(SERVER_THREAD, ScopeDto("all"), "t"), ids.map { turnDto(it, state = "complete") }, hasMore, nextBefore))

    @Test
    fun anEarlierPageThatCannotMakeProgressIsNotAdoptedAndAnEmptyOneEndsTheHistory() = runTest {
        val (localId, _) = accepted()
        val a = serverId(1)
        val b = serverId(2)
        val c = serverId(3)
        api.historyReads += { page(b, c, hasMore = true, nextBefore = b) }
        repo.refreshHistory(localId)
        assertEquals(b, cache.threadOnce(localId)!!.olderCursor)

        // A page that holds the turn it was asked to precede would be asked for again, for ever.
        api.historyReads += { page(a, b, hasMore = true, nextBefore = a) }
        assertEquals(ReadOutcome.Unavailable("malformed_response"), repo.loadEarlier(localId))
        // More turns claimed with nowhere to go on, or a cursor that is not the page's oldest turn.
        api.historyReads += { page(a, hasMore = true, nextBefore = null) }
        assertEquals(ReadOutcome.Unavailable("malformed_response"), repo.loadEarlier(localId))
        api.historyReads += { page(a, hasMore = true, nextBefore = c) }
        assertEquals(ReadOutcome.Unavailable("malformed_response"), repo.loadEarlier(localId))
        api.historyReads += { page(a, hasMore = false, nextBefore = a) }
        assertEquals(ReadOutcome.Unavailable("malformed_response"), repo.loadEarlier(localId))
        assertEquals(b, cache.threadOnce(localId)!!.olderCursor)
        assertNull(cache.turnOnce(a))

        // An empty page cannot lead anywhere, whatever it claims: the history ends and the button goes.
        api.historyReads += { page(hasMore = true, nextBefore = a) }
        assertEquals(ReadOutcome.Updated, repo.loadEarlier(localId))
        assertNull(cache.threadOnce(localId)!!.olderCursor)
        assertFalse(cache.threadOnce(localId)!!.olderAvailable)
        assertEquals(ReadOutcome.NothingYet, repo.loadEarlier(localId))
    }

    @Test
    fun aLateEarlierPageCannotMoveACursorThatAnotherReadAlreadyMoved() = runTest {
        val (localId, mine) = accepted()
        val order = (0 until 129).map { serverId(it) } + mine
        api.historyReads += worker(order)
        repo.refreshHistory(localId)
        val gate = CompletableDeferred<Unit>()
        api.historyReads += {
            gate.await()
            worker(order)()
        }
        val late = async { repo.loadEarlier(localId) }
        runCurrent()
        // While that request is out, the cursor is moved by a read that started later and finished first.
        api.historyReads += worker(order)
        assertEquals(ReadOutcome.Updated, repo.loadEarlier(localId))
        assertEquals(order[30], cache.threadOnce(localId)!!.olderCursor)
        gate.complete(Unit)
        assertEquals(ReadOutcome.Stale, late.await())
        assertEquals(order[30], cache.threadOnce(localId)!!.olderCursor)
    }

    @Test
    fun historyForAnotherThreadOrScopeIsRejected() = runTest {
        val (localId, _) = accepted()
        api.historyReads += { ReadResult.Ok(HistoryDto(ThreadDto(SERVER_THREAD, ScopeDto("all"), "t"), listOf(turnDto("44444444-4444-4444-8444-444444444444", threadId = "55555555-5555-4555-8555-555555555555")), false)) }
        assertEquals(ReadOutcome.Unavailable("mismatched_response"), repo.refreshHistory(localId))
        api.historyReads += { ReadResult.Ok(HistoryDto(ThreadDto(SERVER_THREAD, ScopeDto("concern", CONCERN), "t"), emptyList(), false)) }
        assertEquals(ReadOutcome.Unavailable("mismatched_response"), repo.refreshHistory(localId))
        assertEquals(1, cache.turnsOnce(localId).size)
    }

    @Test
    fun aMalformedOrUnknownServerStateIsAnErrorNotSomethingToSkip() = runTest {
        val (_, id) = accepted()
        api.turnReads += { ReadResult.Ok(turnDto(id, state = "teleported")) }
        assertEquals(ReadOutcome.Unavailable("malformed_response"), repo.readTurn(id))
        api.turnReads += { ReadResult.Ok(turnDto(id, state = "complete", answer = "x", claims = listOf(claim(citation(SOURCE_1), basis = "certainty")))) }
        assertEquals(ReadOutcome.Unavailable("malformed_response"), repo.readTurn(id))
        api.turnReads += { ReadResult.Ok(turnDto("55555555-5555-4555-8555-555555555555", state = "complete")) }
        assertEquals(ReadOutcome.Unavailable("mismatched_response"), repo.readTurn(id))
        assertEquals(ServerState.PENDING, cache.turnOnce(id)!!.state)
    }

    @Test
    fun cancellingAQuestionThatWasNeverSentNeedsNoRequest() = runTest {
        val t = thread()
        val id = ask(t.localId)
        assertEquals(CancelOutcome.Withdrawn, repo.cancel(id))
        assertTrue(api.log.isEmpty())
        assertEquals(TurnPhase.WITHDRAWN, cache.turnOnce(id)!!.phase())
        // A withdrawn question is never dispatched later.
        assertEquals(Settle.DONE, repo.dispatch(id))
        assertTrue(api.log.isEmpty())
    }

    @Test
    fun cancellingWaitingWorkAsksTheWorkerAndReadsTheResultBack() = runTest {
        val (_, id) = accepted()
        api.cancelResults += CancelResult.Cancelled
        api.turnReads += { ReadResult.Ok(turnDto(id, state = "cancelled", reason = "owner_cancelled", completedAt = "2026-10-08T10:00:30.000Z")) }
        assertEquals(CancelOutcome.Cancelled, repo.cancel(id))
        assertEquals(TurnPhase.CANCELLED, cache.turnOnce(id)!!.phase())
        // A fresh cancel key, never the turn's identity.
        assertNotEquals(id, api.cancelKeys.single())
    }

    @Test
    fun anAlreadyDispatchedCancelIsRefusedHonestlyAndTheNoteStays() = runTest {
        val (_, id) = accepted()
        api.cancelResults += CancelResult.AlreadyDispatched
        api.turnReads += { ReadResult.Ok(turnDto(id, state = "running")) }
        assertEquals(CancelOutcome.AlreadyDispatched, repo.cancel(id))
        val turn = cache.turnOnce(id)!!
        assertEquals(CancelNote.ALREADY_DISPATCHED, turn.cancelNote)
        assertEquals(TurnPhase.RUNNING, turn.phase())
    }

    @Test
    fun aNotCancellableAnswerAndAFinishedTurnAreShownAsSuch() = runTest {
        val (_, id) = accepted()
        api.cancelResults += CancelResult.NotCancellable("complete")
        api.turnReads += { ReadResult.Ok(turnDto(id, state = "complete", answer = "Done.")) }
        assertEquals(CancelOutcome.NotCancellable, repo.cancel(id))
        assertEquals(CancelNote.NOT_CANCELLABLE, cache.turnOnce(id)!!.cancelNote)
        // Now known to be finished, so no further cancel request is made.
        assertEquals(CancelOutcome.NotCancellable, repo.cancel(id))
        assertEquals(1, api.cancelKeys.size)
    }

    @Test
    fun anUnansweredCancelIsReadBackAndNotAssumed() = runTest {
        val (_, id) = accepted()
        api.cancelResults += CancelResult.NoAnswer
        api.turnReads += { ReadResult.Ok(turnDto(id, state = "cancelled")) }
        assertEquals(CancelOutcome.NotConfirmed, repo.cancel(id))
        assertEquals(TurnPhase.CANCELLED, cache.turnOnce(id)!!.phase())
    }

    @Test
    fun cancellingAnUnconfirmedSendWithdrawsItOnlyIfTheWorkerHasNoRecordAndNeverSendsItAfter() = runTest {
        val t = thread()
        val id = ask(t.localId)
        api.threadResults += created()
        api.postResults += PostResult.NoAnswer
        repo.dispatch(id)
        api.turnReads += { ReadResult.Unavailable("not_found") }
        assertEquals(CancelOutcome.Withdrawn, repo.cancel(id))
        assertEquals(TurnPhase.WITHDRAWN, cache.turnOnce(id)!!.phase())
        val report = repo.recover()
        assertFalse(report.waiting)
        assertEquals(1, api.posted.size)
        assertFalse(api.log.contains("cancel"))
    }

    @Test
    fun cancellingAnUnconfirmedSendTheWorkerDoesHaveGoesThroughTheWorker() = runTest {
        val t = thread()
        val id = ask(t.localId)
        api.threadResults += created()
        api.postResults += PostResult.NoAnswer
        repo.dispatch(id)
        api.turnReads += { ReadResult.Ok(turnDto(id, state = "pending")) }
        api.cancelResults += CancelResult.Cancelled
        api.turnReads += { ReadResult.Ok(turnDto(id, state = "cancelled")) }
        assertEquals(CancelOutcome.Cancelled, repo.cancel(id))
        assertEquals(listOf("thread", "post", "turn", "cancel", "turn"), api.log)
    }

    @Test
    fun recoverSendsStoredQuestionsReadsBackTheRestAndSkipsFinishedOnes() = runTest {
        val (localId, first) = accepted()
        val second = ask(localId, "And the other one?")
        api.postResults += PostResult.Accepted("pending")
        api.turnReads += { ReadResult.Ok(turnDto(first, state = "complete", answer = "Yes.")) }
        val report = repo.recover()
        assertTrue(report.waiting)
        assertEquals(Dispatch.ACCEPTED, cache.turnOnce(second)!!.dispatch)
        assertEquals(TurnPhase.COMPLETE, cache.turnOnce(first)!!.phase())

        api.turnReads += { ReadResult.Ok(turnDto(second, state = "complete", answer = "No.", content = "And the other one?")) }
        assertFalse(repo.recover().waiting)
        // Finished turns cost no request.
        val before = api.log.size
        repo.recover()
        assertEquals(before, api.log.size)
        assertNotNull(cache.turnOnce(second)!!.answer)
    }

    @Test
    fun editingAgainNeverCostsTheDraftOrTheOriginal() = runTest {
        val t = thread()
        val id = ask(t.localId, "The original")
        api.threadResults += created()
        api.postResults += PostResult.Refused("invalid_request")
        repo.dispatch(id)
        // Other text in the draft: the refused question stays, nothing is replaced.
        repo.setDraft(t.localId, "Something I started typing")
        assertEquals(EditAgain.DraftInUse, repo.editAgain(id))
        assertEquals("The original", cache.turnOnce(id)?.content)
        assertEquals("Something I started typing", cache.threadOnce(t.localId)!!.draft)
        // Once the draft is empty (or already the same text) it is restored and the question goes.
        repo.setDraft(t.localId, "  ")
        assertEquals(EditAgain.Restored("The original"), repo.editAgain(id))
        assertNull(cache.turnOnce(id))
        assertEquals("The original", cache.threadOnce(t.localId)!!.draft)
        assertEquals(EditAgain.Unavailable, repo.editAgain(id))
    }

    @Test
    fun onlyARefusedOrWithdrawnQuestionCanBeEditedAgain() = runTest {
        val (_, id) = accepted()
        assertEquals(EditAgain.Unavailable, repo.editAgain(id))
        assertNotNull(cache.turnOnce(id))
        val queued = ask(thread().localId, "Still queued")
        assertEquals(EditAgain.Unavailable, repo.editAgain(queued))
        assertEquals(CancelOutcome.Withdrawn, repo.cancel(queued))
        assertEquals(EditAgain.Restored("Still queued"), repo.editAgain(queued))
    }

    @Test
    fun aCancelWaitsForASendInFlightAndThenCancelsWhatTheWorkerAccepted() = runTest {
        val t = thread()
        val id = ask(t.localId)
        api.threadResults += created()
        api.postResults += PostResult.Accepted("pending")
        api.postGate = CompletableDeferred()
        val sending = async { repo.dispatch(id) }
        runCurrent()
        val cancelling = async { repo.cancel(id) }
        runCurrent()
        // The cancel has not raced past the send: no request for it yet, and the send has not finished.
        assertFalse(api.log.contains("cancel"))
        assertTrue(sending.isActive)
        assertTrue(cancelling.isActive)

        api.cancelResults += CancelResult.Cancelled
        api.turnReads += { ReadResult.Ok(turnDto(id, state = "cancelled", reason = "owner_cancelled")) }
        api.postGate!!.complete(Unit)
        assertEquals(Settle.DONE, sending.await())
        assertEquals(CancelOutcome.Cancelled, cancelling.await())
        assertEquals(listOf("thread", "post", "cancel", "turn"), api.log)
        assertEquals(TurnPhase.CANCELLED, cache.turnOnce(id)!!.phase())
        assertEquals(1, api.posted.size)
    }

    @Test
    fun aReadWhoseEchoedTextOrScopeDiffersFromTheStoredQuestionIsNotApplied() = runTest {
        val (_, id) = accepted()
        api.turnReads += { ReadResult.Ok(turnDto(id, state = "complete", content = "A different question", answer = "Done.")) }
        assertEquals(ReadOutcome.Unavailable("mismatched_response"), repo.readTurn(id))
        api.turnReads += { ReadResult.Ok(turnDto(id, state = "complete", scope = ScopeDto("concern", CONCERN), answer = "Done.")) }
        assertEquals(ReadOutcome.Unavailable("mismatched_response"), repo.readTurn(id))
        val turn = cache.turnOnce(id)!!
        assertEquals(ServerState.PENDING, turn.state)
        assertEquals("Did I pay?", turn.content)
        assertNull(turn.answer)
    }

    @Test
    fun aHistoryPageEchoingOtherTextForAQuestionThePhoneHoldsIsNotApplied() = runTest {
        val (localId, id) = accepted()
        api.historyReads += {
            ReadResult.Ok(HistoryDto(ThreadDto(SERVER_THREAD, ScopeDto("all"), "2026-10-08T10:00:00.000Z"), listOf(turnDto(id, state = "complete", content = "Not what I asked", answer = "Done.")), false))
        }
        assertEquals(ReadOutcome.Unavailable("mismatched_response"), repo.refreshHistory(localId))
        assertEquals(ServerState.PENDING, cache.turnOnce(id)!!.state)
    }

    @Test
    fun anAnswerArrivingAfterARestrictionIsHeldAndShownOnlyIfEveryEmailItRestsOnQualifies() = runTest {
        val (_, id) = accepted()
        cache.sources[SOURCE_1] = fact(SOURCE_1)
        cache.sources[SOURCE_2] = fact(SOURCE_2, quarantined = true)
        // The uncited second email was quarantined while the question was out.
        cache.dropRestrictedAnswers(setOf(SOURCE_2))
        assertEquals(Dispatch.ACCEPTED, cache.turnOnce(id)!!.dispatch)
        assertNull(cache.turnOnce(id)!!.localInvalid)
        val reply = turnDto(id, state = "complete", answer = "Paid on Friday.", claims = listOf(claim(citation(SOURCE_1))), context = listOf(ContextDto(SOURCE_1, "hybrid"), ContextDto(SOURCE_2, "hybrid")))
        api.turnReads += { ReadResult.Ok(reply) }
        repo.readTurn(id)
        assertEquals(TurnPhase.UNVERIFIED, cache.turnOnce(id)!!.phase())
        assertNull(cache.turnOnce(id)!!.answer)
        assertTrue(cache.needingCheck().any { it.turnId == id })

        cache.sources[SOURCE_2] = fact(SOURCE_2)
        api.turnReads += { ReadResult.Ok(reply) }
        repo.readTurn(id)
        assertEquals(TurnPhase.COMPLETE, cache.turnOnce(id)!!.phase())
    }

    @Test
    fun aStoredAnswerIsRemovedForGoodWhenAnEmailItRestsOnIsRestrictedAndNoLaterReadBringsItBack() = runTest {
        val (_, id) = accepted()
        cache.sources[SOURCE_1] = fact(SOURCE_1)
        cache.sources[SOURCE_2] = fact(SOURCE_2)
        val reply = turnDto(id, state = "complete", answer = "Paid on Friday.", claims = listOf(claim(citation(SOURCE_1))), context = listOf(ContextDto(SOURCE_1, "hybrid"), ContextDto(SOURCE_2, "hybrid")))
        api.turnReads += { ReadResult.Ok(reply) }
        repo.readTurn(id)
        assertEquals(TurnPhase.COMPLETE, cache.turnOnce(id)!!.phase())

        cache.sources[SOURCE_2] = fact(SOURCE_2, privacy = "no_ai")
        assertEquals(1, cache.dropRestrictedAnswers(setOf(SOURCE_2)))
        assertEquals(TurnPhase.INVALIDATED, cache.turnOnce(id)!!.phase())
        // The email is later allowed again and the Worker still has the answer: it stays gone on this phone.
        cache.sources[SOURCE_2] = fact(SOURCE_2)
        api.turnReads += { ReadResult.Ok(reply) }
        repo.readTurn(id)
        assertNull(cache.turnOnce(id)!!.answer)
        assertEquals(LocalInvalid.SOURCE_CHANGED, cache.turnOnce(id)!!.localInvalid)
        assertFalse(cache.needingCheck().any { it.turnId == id })
    }

    @Test
    fun aQuestionNeverSentIsNotPoisonedByAnEmailChangeAndIsAnsweredNormally() = runTest {
        val t = thread()
        val id = ask(t.localId)
        cache.sources[SOURCE_1] = fact(SOURCE_1, quarantined = true)
        cache.dropRestrictedAnswers(setOf(SOURCE_1))
        cache.sources[SOURCE_1] = fact(SOURCE_1)
        assertNull(cache.turnOnce(id)!!.localInvalid)
        api.threadResults += created()
        api.postResults += PostResult.Accepted("pending")
        assertEquals(Settle.DONE, repo.dispatch(id))
        api.turnReads += { ReadResult.Ok(turnDto(id, state = "complete", answer = "Paid on Friday.", claims = listOf(claim(citation(SOURCE_1))))) }
        repo.readTurn(id)
        assertEquals(TurnPhase.COMPLETE, cache.turnOnce(id)!!.phase())
    }

    @Test
    fun anAnswerThatBreaksTheContractIsRejectedAndOneWithoutContextIsHeldAndReadAgain() = runTest {
        val (_, id) = accepted()
        cache.sources[SOURCE_1] = fact(SOURCE_1)
        val noCitation = claim(basis = "evidence")
        api.turnReads += { ReadResult.Ok(turnDto(id, state = "complete", answer = "Paid.", claims = listOf(noCitation))) }
        repo.readTurn(id)
        assertEquals(TurnPhase.ANSWER_REJECTED, cache.turnOnce(id)!!.phase())
        assertNull(cache.turnOnce(id)!!.answer)
        assertFalse(cache.needingCheck().any { it.turnId == id })

        api.turnReads += { ReadResult.Ok(turnDto(id, state = "complete", answer = "Paid.", context = null)) }
        repo.readTurn(id)
        assertEquals(TurnPhase.UNVERIFIED, cache.turnOnce(id)!!.phase())
        assertTrue(cache.needingCheck().any { it.turnId == id })
    }

    @Test
    fun signOutAsksTheWorkerToCancelOnlyWhatItMayHoldAndNeverSendsAnything() = runTest {
        val (localId, first) = accepted()
        val queued = ask(localId, "Never sent")
        val done = ask(localId, "Already answered")
        api.postResults += PostResult.Accepted("pending")
        repo.dispatch(done)
        api.turnReads += { ReadResult.Ok(turnDto(done, state = "complete", answer = "Yes.", content = "Already answered")) }
        repo.readTurn(done)
        // One question whose send is unconfirmed.
        val unsure = ask(localId, "Unsure")
        api.postResults += PostResult.NoAnswer
        repo.dispatch(unsure)
        val postsBefore = api.posted.size

        api.cancelResults += CancelResult.Cancelled
        api.cancelResults += CancelResult.AlreadyDispatched
        assertEquals(2, repo.cancelWaiting())
        assertEquals(2, api.cancelKeys.size)
        assertEquals(postsBefore, api.posted.size)
        assertFalse(api.log.takeLast(2).contains("post"))
        // The phone does not claim any of it worked: nothing about the turns changed.
        assertEquals(Dispatch.QUEUED, cache.turnOnce(queued)!!.dispatch)
        assertEquals(Dispatch.ATTEMPTED, cache.turnOnce(unsure)!!.dispatch)
        assertEquals(ServerState.PENDING, cache.turnOnce(first)!!.state)
    }

    @Test
    fun signOutCancelsStopWhenTheSessionHasEnded() = runTest {
        val (localId, _) = accepted()
        api.postResults += PostResult.Accepted("pending")
        repo.dispatch(ask(localId, "Second"))
        api.cancelResults += CancelResult.SessionExpired
        assertEquals(1, repo.cancelWaiting())
        assertEquals(1, api.cancelKeys.size)
    }

    @Test
    fun whatSignOutWouldDiscardIsCountedApartAndNothingFinishedIsCounted() = runTest {
        val (localId, _) = accepted()
        ask(localId, "Queued one")
        val unsure = ask(localId, "Unsure one")
        api.postResults += PostResult.NoAnswer
        repo.dispatch(unsure)
        repo.setDraft(localId, "half typed")
        val unsent = repo.unsent().first()
        assertEquals(2, unsent.unconfirmedQuestions)
        assertEquals(1, unsent.drafts)
        assertEquals(1, unsent.atWorker)
        assertTrue(unsent.any)
        cache.clear()
        assertFalse(repo.unsent().first().any)
    }
}
