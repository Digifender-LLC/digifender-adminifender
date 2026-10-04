package com.digifender.adminifender.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcernTest {
    private val base = Concern(
        id = "c1", revision = 4, title = "Renewal", status = Status.OPEN, importance = Importance.ROUTINE, criticalBasis = null,
        readAtMs = null, acknowledgedAtMs = null, resolvedAtMs = null, snoozeUntilMs = null, waiting = null,
        due = Due(atMs = 1_000, zone = "UTC", sourceText = "Friday", revision = 1), sourceIds = listOf("s1"), updatedAtMs = 0,
    )

    @Test
    fun controlsMatchTheBackendTable() {
        assertEquals(
            listOf(ActionKind.ACKNOWLEDGE, ActionKind.RESOLVE, ActionKind.SNOOZE, ActionKind.WAIT, ActionKind.SET_DUE, ActionKind.MARK_READ),
            availableActions(Status.OPEN),
        )
        assertEquals(listOf(ActionKind.REOPEN, ActionKind.SET_DUE, ActionKind.MARK_READ), availableActions(Status.RESOLVED))
        assertEquals(
            listOf(ActionKind.ACKNOWLEDGE, ActionKind.RESOLVE, ActionKind.REOPEN, ActionKind.SNOOZE, ActionKind.SET_DUE, ActionKind.MARK_READ),
            availableActions(Status.SNOOZED),
        )
    }

    @Test
    fun optimisticChangesKeepTheServerRevision() {
        val resolved = applyOptimistic(base, ConcernAction.Resolve, 50) as Transition.Applied
        assertEquals(Status.RESOLVED, resolved.concern.status)
        assertEquals(4, resolved.concern.revision)
        assertEquals(50L, resolved.concern.resolvedAtMs)
        assertEquals(Transition.NotAvailable(Status.RESOLVED, ActionKind.WAIT), applyOptimistic(resolved.concern, ConcernAction.Wait(null, null, null), 60))
    }

    @Test
    fun readAndAcknowledgeLeaveStatusAndImportanceAlone() {
        val read = (applyOptimistic(base, ConcernAction.MarkRead, 10) as Transition.Applied).concern
        val acknowledged = (applyOptimistic(read, ConcernAction.Acknowledge, 20) as Transition.Applied).concern
        assertEquals(Status.OPEN, acknowledged.status)
        assertEquals(Importance.ROUTINE, acknowledged.importance)
        assertEquals(10L, acknowledged.readAtMs)
        assertEquals(20L, acknowledged.acknowledgedAtMs)
    }

    @Test
    fun snoozeMustBeInTheFutureAndReopenClearsIt() {
        assertEquals(Transition.SnoozeInPast, applyOptimistic(base, ConcernAction.Snooze(10), 10))
        val snoozed = (applyOptimistic(base, ConcernAction.Snooze(100), 10) as Transition.Applied).concern
        val reopened = (applyOptimistic(snoozed, ConcernAction.Reopen, 20) as Transition.Applied).concern
        assertEquals(Status.OPEN, reopened.status)
        assertNull(reopened.snoozeUntilMs)
    }

    @Test
    fun setDueKeepsSourceWordingUnlessReplacedAndRaisesTheDueRevision() {
        val moved = (applyOptimistic(base, ConcernAction.SetDue(2_000, "Europe/Berlin", null), 10) as Transition.Applied).concern
        assertEquals(Due(2_000, "Europe/Berlin", "Friday", 2), moved.due)
        val reworded = (applyOptimistic(moved, ConcernAction.SetDue(null, null, "Next month"), 20) as Transition.Applied).concern
        assertEquals("Next month", reworded.due?.sourceText)
        assertTrue(Status.fromWire("snoozed") == Status.SNOOZED && Importance.fromWire("critical") == Importance.CRITICAL && Status.fromWire("x") == null)
    }
}
