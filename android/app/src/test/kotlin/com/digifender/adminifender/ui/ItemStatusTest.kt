package com.digifender.adminifender.ui

import com.digifender.adminifender.core.model.Concern
import com.digifender.adminifender.core.model.Due
import com.digifender.adminifender.core.model.Importance
import com.digifender.adminifender.core.model.Status
import com.digifender.adminifender.core.model.Waiting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ItemStatusTest {
    private val now = 1_790_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun concern(status: Status = Status.OPEN, dueAt: Long? = null, waitingOn: String? = null) = Concern(
        id = "c", revision = 1, title = "t", status = status, importance = Importance.ROUTINE, criticalBasis = null,
        readAtMs = null, acknowledgedAtMs = null, resolvedAtMs = null, snoozeUntilMs = null,
        waiting = if (status == Status.WAITING) Waiting(null, waitingOn, null) else null,
        due = dueAt?.let { Due(it, "UTC", null, 1) }, sourceIds = emptyList(), updatedAtMs = now,
    )

    @Test fun `past due is overdue and beats waiting`() =
        assertEquals(ItemStatus.Overdue(now - 1), itemStatus(concern(Status.WAITING, now - 1, "Lakeview"), now))

    @Test fun `due within a week is due soon, later is neutral`() {
        assertEquals(ItemStatus.DueSoon(now + 7 * day), itemStatus(concern(dueAt = now + 7 * day), now))
        assertEquals(ItemStatus.DueLater(now + 8 * day), itemStatus(concern(dueAt = now + 8 * day), now))
    }

    @Test fun `waiting names the party only when there is one`() {
        assertEquals(ItemStatus.Waiting("Lakeview"), itemStatus(concern(Status.WAITING, waitingOn = "Lakeview"), now))
        assertEquals(ItemStatus.Waiting(null), itemStatus(concern(Status.WAITING, waitingOn = " "), now))
    }

    @Test fun `resolved is done whatever the date, and plain open items show no pill`() {
        assertEquals(ItemStatus.Done, itemStatus(concern(Status.RESOLVED, now - day), now))
        assertNull(itemStatus(concern(), now))
    }
}
