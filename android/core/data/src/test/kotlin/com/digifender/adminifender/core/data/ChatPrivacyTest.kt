package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.ChatScope
import com.digifender.adminifender.core.data.network.ScopeKind
import com.digifender.adminifender.core.data.repository.AnswerProblem
import com.digifender.adminifender.core.data.repository.ChatAnswer
import com.digifender.adminifender.core.data.repository.ChatCitation
import com.digifender.adminifender.core.data.repository.ChatClaim
import com.digifender.adminifender.core.data.repository.ChatProposal
import com.digifender.adminifender.core.data.repository.ChatThread
import com.digifender.adminifender.core.data.repository.ChatTurn
import com.digifender.adminifender.core.data.repository.ClaimBasis
import com.digifender.adminifender.core.data.repository.ContextCheck
import com.digifender.adminifender.core.data.repository.ContextSource
import com.digifender.adminifender.core.data.repository.CreateState
import com.digifender.adminifender.core.data.repository.Dispatch
import com.digifender.adminifender.core.data.repository.LocalInvalid
import com.digifender.adminifender.core.data.repository.PageKind
import com.digifender.adminifender.core.data.repository.ServerState
import com.digifender.adminifender.core.data.repository.ServerTurn
import com.digifender.adminifender.core.data.repository.SourceFact
import com.digifender.adminifender.core.data.repository.checkContext
import com.digifender.adminifender.core.data.repository.mergeServerTurn
import com.digifender.adminifender.core.data.repository.planPage
import com.digifender.adminifender.core.data.repository.restricts
import com.digifender.adminifender.core.data.repository.staleAnswers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPrivacyTest {
    private fun thread(scope: ChatScope = ChatScope(ScopeKind.ALL)) = ChatThread("t1", "k", SERVER_THREAD, scope, CreateState.CREATED, null, "", null, false, 1, 1)

    /** Cites SOURCE_1 only, but the model was given SOURCE_2 as well. */
    private val answer = ChatAnswer(
        "Paid.",
        listOf(ChatClaim("Paid", ClaimBasis.EVIDENCE, listOf(ChatCitation("c1", SOURCE_1, null, null, 0, 4, "Paid")))),
        emptyList(),
        emptyList(),
        context(SOURCE_1, SOURCE_2),
    )

    private fun turn(
        answer: ChatAnswer? = this.answer,
        state: ServerState? = ServerState.COMPLETE,
        dispatch: Dispatch = Dispatch.ACCEPTED,
        invalid: LocalInvalid? = null,
        id: String = "turn-1",
    ) = ChatTurn(id, "t1", "Did I pay?", ChatScope(ScopeKind.ALL), dispatch, null, state, null, null, answer, false, false, invalid, null, 500, 1_000, null)

    private fun server(answer: ChatAnswer? = this.answer, problem: AnswerProblem? = null, state: ServerState = ServerState.COMPLETE) =
        ServerTurn("turn-1", SERVER_THREAD, ChatScope(ScopeKind.ALL), "Did I pay?", state, null, null, answer, problem, false, false, 500, 1_000)

    private val both = mapOf(SOURCE_1 to fact(SOURCE_1), SOURCE_2 to fact(SOURCE_2))

    @Test
    fun onlyAChangeThatReducesWhatAModelMayUseRestricts() {
        val ok = fact(SOURCE_1)
        assertTrue(restricts(ok, null))
        assertTrue(restricts(ok, ok.copy(quarantined = true)))
        assertTrue(restricts(ok, ok.copy(privacy = "no_ai")))
        assertTrue(restricts(ok, ok.copy(privacy = "local_only")))
        assertTrue(restricts(ok, ok.copy(privacy = "a_policy_added_later")))
        assertTrue(restricts(ok, ok.copy(state = "deleting")))
        // Ordinary revisions, loosening, new mail and mail the phone never held cannot have hurt a stored answer.
        assertFalse(restricts(ok, ok.copy()))
        assertFalse(restricts(null, ok))
        assertFalse(restricts(null, null))
        assertFalse(restricts(null, ok.copy(quarantined = true)))
        assertFalse(restricts(ok.copy(privacy = "local_only"), ok))
        assertFalse(restricts(ok.copy(quarantined = true), ok.copy(quarantined = true, privacy = "no_ai")))
        assertFalse(restricts(ok.copy(quarantined = true), ok))
    }

    @Test
    fun theWholeContextDecidesNotOnlyTheCitedEmails() {
        assertEquals(ContextCheck.HOLDS, checkContext(answer.context, both))
        // SOURCE_2 was given to the model but never cited.
        assertEquals(ContextCheck.RESTRICTED, checkContext(answer.context, both + (SOURCE_2 to fact(SOURCE_2, quarantined = true))))
        assertEquals(ContextCheck.RESTRICTED, checkContext(answer.context, both + (SOURCE_2 to fact(SOURCE_2, privacy = "no_ai"))))
        assertEquals(ContextCheck.RESTRICTED, checkContext(answer.context, both + (SOURCE_2 to fact(SOURCE_2, state = "staged"))))
        assertEquals(ContextCheck.MISSING, checkContext(answer.context, both - SOURCE_2))
        // Restricted outranks absent: one firm reason is enough.
        assertEquals(ContextCheck.RESTRICTED, checkContext(answer.context, mapOf(SOURCE_1 to fact(SOURCE_1, quarantined = true))))
        assertEquals(ContextCheck.HOLDS, checkContext(emptyList(), emptyMap()))
    }

    @Test
    fun anEmailStricterThanWhenItWasUsedFailsAndOneNotStricterHolds() {
        val hybridThen = context(SOURCE_1)
        assertEquals(ContextCheck.RESTRICTED, checkContext(hybridThen, mapOf(SOURCE_1 to fact(SOURCE_1, privacy = "local_only"))))
        val localThen = listOf(ContextSource(SOURCE_1, "local_only"))
        assertEquals(ContextCheck.HOLDS, checkContext(localThen, mapOf(SOURCE_1 to fact(SOURCE_1, privacy = "local_only"))))
        assertEquals(ContextCheck.HOLDS, checkContext(localThen, mapOf(SOURCE_1 to fact(SOURCE_1, privacy = "hybrid"))))
        assertEquals(ContextCheck.RESTRICTED, checkContext(localThen, mapOf(SOURCE_1 to fact(SOURCE_1, privacy = "no_ai"))))
    }

    @Test
    fun aStoredAnswerIsRemovedWhenAnyEmailItRestsOnStopsQualifying() {
        val turns = listOf(turn())
        assertEquals(listOf("turn-1"), staleAnswers(setOf(SOURCE_2), turns, both + (SOURCE_2 to fact(SOURCE_2, quarantined = true))))
        assertEquals(listOf("turn-1"), staleAnswers(setOf(SOURCE_2), turns, both - SOURCE_2))
        assertEquals(listOf("turn-1"), staleAnswers(setOf(SOURCE_1), turns, both + (SOURCE_1 to fact(SOURCE_1, privacy = "no_ai"))))
        // The change did not make it stale: the email is still fine, or the answer is about other emails.
        assertTrue(staleAnswers(setOf(SOURCE_2), turns, both).isEmpty())
        assertTrue(staleAnswers(setOf(OTHER_SOURCE), turns, both + (SOURCE_2 to fact(SOURCE_2, quarantined = true))).isEmpty())
        assertTrue(staleAnswers(emptySet(), turns, emptyMap()).isEmpty())
    }

    @Test
    fun mailArrivingAroundTheAnswerNeverCostsAnAnswerAndNeedsNoClock() {
        // A spam message synced after an answer, however its received time compares, is not in any answer's context.
        val turns = listOf(turn(id = "a"), turn(id = "b"))
        assertTrue(staleAnswers(setOf(OTHER_SOURCE), turns, both + (OTHER_SOURCE to fact(OTHER_SOURCE, quarantined = true))).isEmpty())
    }

    @Test
    fun aQuestionWithoutAnAnswerHoldsNothingToRemoveAndIsNeverMarked() {
        val changed = setOf(SOURCE_1, SOURCE_2)
        val gone = emptyMap<String, SourceFact>()
        val idle = listOf(
            turn(answer = null, state = null, dispatch = Dispatch.QUEUED, id = "queued"),
            turn(answer = null, state = null, dispatch = Dispatch.ATTEMPTED, id = "attempted"),
            turn(answer = null, state = ServerState.RUNNING, id = "running"),
            turn(answer = null, state = ServerState.PENDING, id = "pending"),
            turn(answer = null, state = ServerState.FAILED, id = "failed"),
            turn(answer = null, invalid = LocalInvalid.UNVERIFIED, id = "unverified"),
            turn(answer = null, invalid = LocalInvalid.SOURCE_CHANGED, id = "removed"),
        )
        assertTrue(staleAnswers(changed, idle, gone).isEmpty())
    }

    @Test
    fun anAnswerIsStoredOnlyWhenEveryEmailItRestsOnHolds() {
        assertNotNull(mergeServerTurn(null, server(), "t1", both, 5).answer)
        val broken = listOf(
            emptyMap<String, SourceFact>(),
            mapOf(SOURCE_1 to fact(SOURCE_1)),
            both + (SOURCE_2 to fact(SOURCE_2, quarantined = true)),
            both + (SOURCE_2 to fact(SOURCE_2, privacy = "no_ai")),
            both + (SOURCE_1 to fact(SOURCE_1, state = "staged")),
        )
        for (facts in broken) {
            val held = mergeServerTurn(null, server(), "t1", facts, 5)
            assertNull(held.answer)
            assertEquals(LocalInvalid.UNVERIFIED, held.localInvalid)
        }
    }

    @Test
    fun historyReadAfterARestrictionCannotBringTheAnswerBack() {
        // The phone never held this turn. The uncited email was quarantined and synced earlier; the page arrives later.
        val facts = both + (SOURCE_2 to fact(SOURCE_2, quarantined = true))
        val plan = planPage(thread(), emptyMap(), listOf(server()), PageKind.Earlier("x", null), true, facts, 5)
        val merged = plan.upserts.single()
        assertNull(merged.answer)
        assertEquals(LocalInvalid.UNVERIFIED, merged.localInvalid)
        assertEquals(Dispatch.ACCEPTED, merged.dispatch)
    }

    @Test
    fun anInFlightQuestionIsJudgedWhenItsAnswerArrivesNotWhenTheEmailChanged() {
        // Restricted while the question was out, then released again: the answer is shown on arrival, nothing was marked.
        val inFlight = turn(answer = null, state = ServerState.PENDING)
        assertTrue(staleAnswers(setOf(SOURCE_2), listOf(inFlight), both + (SOURCE_2 to fact(SOURCE_2, quarantined = true))).isEmpty())
        assertNotNull(mergeServerTurn(inFlight, server(), "t1", both, 5).answer)
        // Still restricted when the answer arrives: held, and decided again on the next read.
        val held = mergeServerTurn(inFlight, server(), "t1", both + (SOURCE_2 to fact(SOURCE_2, quarantined = true)), 5)
        assertNull(held.answer)
        assertEquals(LocalInvalid.UNVERIFIED, held.localInvalid)
    }

    @Test
    fun aQueuedQuestionIsNeverPoisonedByAnEmailChange() {
        val queued = turn(answer = null, state = null, dispatch = Dispatch.QUEUED)
        assertTrue(staleAnswers(setOf(SOURCE_1), listOf(queued), emptyMap()).isEmpty())
        assertNull(queued.localInvalid)
    }

    @Test
    fun anAnswerWithoutCitationsNeedsNoEmails() {
        val plain = server(ChatAnswer("No evidence found.", emptyList(), listOf("Nothing matched"), emptyList(), emptyList()))
        val merged = mergeServerTurn(null, plain, "t1", emptyMap(), 5)
        assertEquals("No evidence found.", merged.answer?.text)
        assertNull(merged.localInvalid)
        // A later restriction anywhere cannot touch an answer that rests on nothing.
        assertTrue(staleAnswers(setOf(SOURCE_1, SOURCE_2), listOf(turn(answer = merged.answer)), emptyMap()).isEmpty())
    }

    @Test
    fun anAnswerTheWorkerCannotExplainIsHeldAndOneThatBreaksTheContractIsRejected() {
        val unknown = mergeServerTurn(null, server(answer = null, problem = AnswerProblem.CONTEXT_UNKNOWN), "t1", both, 5)
        assertNull(unknown.answer)
        assertEquals(LocalInvalid.UNVERIFIED, unknown.localInvalid)
        val rejected = mergeServerTurn(null, server(answer = null, problem = AnswerProblem.MALFORMED), "t1", both, 5)
        assertNull(rejected.answer)
        assertEquals(LocalInvalid.REJECTED, rejected.localInvalid)
        // A rejected answer does not stick: the next read decides afresh.
        assertNotNull(mergeServerTurn(rejected, server(), "t1", both, 6).answer)
        assertNull(mergeServerTurn(rejected, server(), "t1", both, 6).localInvalid)
    }

    @Test
    fun anAnswerRemovedForAChangedEmailIsNeverRestoredByAnyLaterRead() {
        val removed = turn(answer = null, invalid = LocalInvalid.SOURCE_CHANGED)
        val again = mergeServerTurn(removed, server(), "t1", both, 9)
        assertNull(again.answer)
        assertEquals(LocalInvalid.SOURCE_CHANGED, again.localInvalid)
        assertEquals("Did I pay?", again.content)
        // Not even when the server's own answer could not be used at all.
        assertEquals(LocalInvalid.SOURCE_CHANGED, mergeServerTurn(removed, server(answer = null, problem = AnswerProblem.MALFORMED), "t1", both, 9).localInvalid)
    }

    @Test
    fun theSubmittedTextAndScopeSurviveWhatTheWorkerEchoes() {
        val mine = turn(answer = null, state = null, dispatch = Dispatch.ATTEMPTED).copy(content = "  exact\n", scope = ChatScope(ScopeKind.CONCERN, "cccccccc-cccc-4ccc-8ccc-cccccccccccc"))
        val merged = mergeServerTurn(mine, server(), "t1", both, 9)
        assertEquals("  exact\n", merged.content)
        assertEquals(ScopeKind.CONCERN, merged.scope.kind)
        assertEquals(Dispatch.ACCEPTED, merged.dispatch)
    }

    @Test
    fun aPageTurnWhoseEchoedTextOrScopeDiffersFromTheStoredQuestionIsNotApplied() {
        val mine = turn(answer = null, state = null, dispatch = Dispatch.ATTEMPTED)
        val other = server().copy(content = "Something else entirely")
        val otherScope = server().copy(scope = ChatScope(ScopeKind.SOURCE, SOURCE_1))
        for (echo in listOf(other, otherScope)) {
            assertTrue(planPage(thread(), mapOf(mine.turnId to mine), listOf(echo), PageKind.Latest(null), true, both, 5).upserts.isEmpty())
        }
        assertEquals(1, planPage(thread(), mapOf(mine.turnId to mine), listOf(server()), PageKind.Latest(null), true, both, 5).upserts.size)
    }

    @Test
    fun theHistoryCursorIsOnlyEverTheWorkersOwnAndOnlyTheRequestedPageMovesIt() {
        val fresh = thread()
        val loaded = fresh.copy(olderCursor = "cursor-b", historyLoaded = true)
        fun plan(thread: ChatThread, kind: PageKind) = planPage(thread, emptyMap(), emptyList(), kind, true, emptyMap(), 1).let { it.olderCursor to it.historyLoaded }
        // The first newest page sets it; later refreshes of the newest page never move it, whatever they say.
        assertEquals("cursor-a" to true, plan(fresh, PageKind.Latest("cursor-a")))
        assertEquals(null to true, plan(fresh, PageKind.Latest(null)))
        assertEquals("cursor-b" to true, plan(loaded, PageKind.Latest("cursor-newer")))
        assertEquals("cursor-b" to true, plan(loaded, PageKind.Latest(null)))
        // A history that was read to its start stays at its start: a refresh does not offer pages that are already held.
        assertEquals(null to true, plan(fresh.copy(olderCursor = null, historyLoaded = true), PageKind.Latest("cursor-newer")))
        // The page asked for at the current cursor replaces it, with null at the start of the history.
        assertEquals("cursor-c" to true, plan(loaded, PageKind.Earlier("cursor-b", "cursor-c")))
        assertEquals(null to true, plan(loaded, PageKind.Earlier("cursor-b", null)))
        // An answer to some other request cannot move it.
        assertEquals("cursor-b" to true, plan(loaded, PageKind.Earlier("cursor-old", null)))
        assertEquals(null to false, plan(fresh, PageKind.Earlier("cursor-b", "cursor-c")))
        assertEquals("cursor-b" to true, plan(loaded, PageKind.Single))
        assertEquals(null to false, plan(fresh, PageKind.Single))
    }

    @Test
    fun aProposalReplyKeepsOnlyItsOwnTargetInTheContext() {
        val proposing = answer.copy(proposals = listOf(ChatProposal("email.reply", SOURCE_2, "Ask for an itemized invoice.")))
        // The target is one of the emails the answer rests on, so it is judged like the rest.
        assertEquals(ContextCheck.RESTRICTED, checkContext(proposing.context, both + (SOURCE_2 to fact(SOURCE_2, quarantined = true))))
        assertEquals(listOf("turn-1"), staleAnswers(setOf(SOURCE_2), listOf(turn(answer = proposing)), both - SOURCE_2))
    }
}
