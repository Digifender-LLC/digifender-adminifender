package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.sync.ActionCodec
import com.digifender.adminifender.core.model.ConcernAction
import com.digifender.adminifender.core.model.clearDue
import org.junit.Assert.assertEquals
import org.junit.Test

class ActionCodecTest {
    private fun roundTrip(action: ConcernAction): ConcernAction {
        val (kind, args) = ActionCodec.encode(action)
        return ActionCodec.decode(kind, args)
    }

    @Test
    fun aQueuedWaitOrDueEditIsStoredExactlySoAResendAfterProcessDeathIsTheSameRequest() {
        listOf(
            ConcernAction.Wait("Asked for terms", "Landlord", 1_791_028_800_000),
            ConcernAction.Wait(null, null, null),
            ConcernAction.Wait("only a note", null, null),
            ConcernAction.SetDue(1_791_028_800_000, "Europe/London", null),
            ConcernAction.SetDue(1_791_028_800_000, null, null),
            clearDue(),
            ConcernAction.SetDue(null, null, "Next month"),
        ).forEach { assertEquals(it, roundTrip(it)) }
    }

    @Test
    fun textThatLooksLikeJsonStaysText() {
        val tricky = ConcernAction.Wait("\"quoted\" {\"a\":1} \\ end", "O'Brien é中", null)
        assertEquals(tricky, roundTrip(tricky))
    }
}
