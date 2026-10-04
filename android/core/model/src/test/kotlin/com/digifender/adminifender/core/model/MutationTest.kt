package com.digifender.adminifender.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MutationTest {
    private val mutation = PendingMutation("00000000-0000-4000-8000-000000000001", "c1", 4, ConcernAction.Acknowledge, 0)

    @Test
    fun resendsKeepTheSameKeyWithBackoff() {
        val first = decide(mutation, SendResult.NoAnswer) as OutboxDecision.Retry
        assertEquals(mutation.idempotencyKey, first.next.idempotencyKey)
        assertEquals(30_000L, first.delayMs)
        val second = decide(first.next, SendResult.NoAnswer) as OutboxDecision.Retry
        assertEquals(60_000L, second.delayMs)
        assertEquals(60 * 60 * 1000L, retryDelayMs(50))
    }

    @Test
    fun staleOfflineChangesBecomeConflictsNotOverwrites() {
        assertEquals(OutboxDecision.Conflict(6), decide(mutation, SendResult.StaleRevision(6)))
        assertEquals(OutboxDecision.Failed("invalid_transition"), decide(mutation, SendResult.Rejected("invalid_transition")))
        assertEquals(OutboxDecision.HoldForSignIn, decide(mutation, SendResult.SessionExpired))
        assertEquals(OutboxDecision.Remove, decide(mutation, SendResult.Accepted(5)))
    }

    @Test
    fun aNewerServerRevisionFlagsTheQueuedChange() {
        val server = Concern("c1", 5, "t", Status.OPEN, Importance.ROUTINE, null, null, null, null, null, null, null, emptyList(), 0)
        assertTrue(conflictsWith(mutation, server))
        assertFalse(conflictsWith(mutation, server.copy(revision = 4)))
        assertFalse(conflictsWith(mutation, server.copy(id = "c2")))
    }
}
