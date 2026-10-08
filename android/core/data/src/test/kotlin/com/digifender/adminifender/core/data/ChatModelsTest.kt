package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.ChatScope
import com.digifender.adminifender.core.data.network.ScopeKind
import com.digifender.adminifender.core.data.repository.CHAT_POLL_DELAYS_MS
import com.digifender.adminifender.core.data.repository.ChatTurn
import com.digifender.adminifender.core.data.repository.Dispatch
import com.digifender.adminifender.core.data.repository.LocalInvalid
import com.digifender.adminifender.core.data.repository.ServerState
import com.digifender.adminifender.core.data.repository.TurnPhase
import com.digifender.adminifender.core.data.repository.cancellable
import com.digifender.adminifender.core.data.repository.inFlight
import com.digifender.adminifender.core.data.repository.needsCheck
import com.digifender.adminifender.core.data.repository.phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatModelsTest {
    private fun turn(dispatch: Dispatch = Dispatch.ACCEPTED, state: ServerState? = null, invalidated: Boolean = false, withheld: Boolean = false, local: LocalInvalid? = null) =
        ChatTurn("t", "th", "q", ChatScope(ScopeKind.ALL), dispatch, null, state, null, null, null, invalidated, withheld, local, null, 1, null, null)

    @Test
    fun everyStateTheOwnerMustTellApartHasItsOwnPhase() {
        val phases = listOf(
            turn(Dispatch.QUEUED) to TurnPhase.NOT_SENT,
            turn(Dispatch.ATTEMPTED) to TurnPhase.UNCONFIRMED,
            turn(state = ServerState.PENDING) to TurnPhase.WAITING,
            turn(state = null) to TurnPhase.WAITING,
            turn(state = ServerState.RUNNING) to TurnPhase.RUNNING,
            turn(state = ServerState.COMPLETE) to TurnPhase.COMPLETE,
            turn(state = ServerState.FAILED) to TurnPhase.FAILED,
            turn(state = ServerState.UNCERTAIN) to TurnPhase.UNCERTAIN,
            turn(state = ServerState.CANCELLED) to TurnPhase.CANCELLED,
            turn(state = ServerState.COMPLETE, invalidated = true) to TurnPhase.INVALIDATED,
            turn(state = ServerState.COMPLETE, local = LocalInvalid.SOURCE_CHANGED) to TurnPhase.INVALIDATED,
            turn(state = ServerState.COMPLETE, withheld = true) to TurnPhase.WITHHELD,
            turn(state = ServerState.COMPLETE, local = LocalInvalid.UNVERIFIED) to TurnPhase.UNVERIFIED,
            turn(state = ServerState.COMPLETE, local = LocalInvalid.REJECTED) to TurnPhase.ANSWER_REJECTED,
            turn(Dispatch.REFUSED) to TurnPhase.REFUSED,
            turn(Dispatch.WITHDRAWN) to TurnPhase.WITHDRAWN,
        )
        phases.forEach { (t, expected) -> assertEquals(expected, t.phase()) }
        assertEquals(TurnPhase.entries.toSet(), phases.map { it.second }.toSet())
    }

    @Test
    fun invalidatedWinsOverWithheldAndAnUnsentTurnIsNeverReportedAsAnswered() {
        assertEquals(TurnPhase.INVALIDATED, turn(state = ServerState.COMPLETE, invalidated = true, withheld = true).phase())
        assertEquals(TurnPhase.NOT_SENT, turn(Dispatch.QUEUED, state = ServerState.COMPLETE).phase())
        assertEquals(TurnPhase.UNCONFIRMED, turn(Dispatch.ATTEMPTED, state = ServerState.COMPLETE).phase())
    }

    @Test
    fun onlyWorkThatCannotHaveStartedIsCancellable() {
        assertTrue(turn(Dispatch.QUEUED).cancellable)
        assertTrue(turn(Dispatch.ATTEMPTED).cancellable)
        assertTrue(turn(state = ServerState.PENDING).cancellable)
        assertFalse(turn(state = ServerState.RUNNING).cancellable)
        assertFalse(turn(state = ServerState.COMPLETE).cancellable)
        assertFalse(turn(Dispatch.WITHDRAWN).cancellable)
        assertFalse(turn(Dispatch.REFUSED).cancellable)
    }

    @Test
    fun whatIsWorthReadingAgain() {
        assertTrue(turn(Dispatch.QUEUED).inFlight)
        assertTrue(turn(state = ServerState.RUNNING).inFlight)
        assertFalse(turn(state = ServerState.UNCERTAIN).inFlight)
        assertFalse(turn(state = ServerState.COMPLETE).needsCheck)
        assertTrue(turn(state = ServerState.COMPLETE, local = LocalInvalid.UNVERIFIED).needsCheck)
        assertFalse(turn(state = ServerState.COMPLETE, local = LocalInvalid.SOURCE_CHANGED).needsCheck)
        // A rejected answer breaks the contract and reading it again would only bring the same answer back.
        assertFalse(turn(state = ServerState.COMPLETE, local = LocalInvalid.REJECTED).needsCheck)
        assertFalse(turn(Dispatch.WITHDRAWN).needsCheck)
    }

    @Test
    fun theScheduleIsOneTwoFiveAndTenSeconds() {
        assertEquals(listOf(1_000L, 2_000L, 5_000L, 10_000L), CHAT_POLL_DELAYS_MS)
    }
}
