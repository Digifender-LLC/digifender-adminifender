package com.digifender.adminifender.ui

import com.digifender.adminifender.core.model.ConcernAction
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConcernFormsTest {
    @Test
    fun rejectsNonexistentDateAndDstGapInsteadOfMovingTheDeadline() {
        assertEquals(ConcernFormResult.Invalid(ConcernFormError.DATE_TIME), dueForm(ConcernTimeFields("2026-02-30", "09:00", "UTC")))
        assertEquals(ConcernFormResult.Invalid(ConcernFormError.NONEXISTENT_TIME), dueForm(ConcernTimeFields("2026-03-29", "02:30", "Europe/Berlin")))
        assertEquals(ConcernFormResult.Invalid(ConcernFormError.ZONE), dueForm(ConcernTimeFields("2026-10-07", "09:00", "Mars/Olympus")))
    }

    @Test
    fun serverRejectedZoneAliasesAreRefusedAndStoredZoneControlsTheDisplayedClock() {
        for (zone in listOf("Z", "UT", "+01:00")) {
            assertEquals(ConcernFormResult.Invalid(ConcernFormError.ZONE), dueForm(ConcernTimeFields("2026-10-07", "09:00", zone)))
        }
        val old = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val displayed = dueDateTime(Instant.parse("2026-10-07T08:00:00Z").toEpochMilli(), "Europe/London", Locale.UK)
            assertTrue(displayed.contains("09:00"))
            assertTrue(displayed.contains("Europe/London"))
            assertFalse(displayed.contains("04:00"))
        } finally { TimeZone.setDefault(old) }
    }

    @Test
    fun autumnOverlapRequiresOneOfTheActualOffsets() {
        val fields = ConcernTimeFields("2026-10-25", "02:30", "Europe/Berlin")
        assertEquals(ConcernFormResult.Invalid(ConcernFormError.AMBIGUOUS_TIME, listOf("+02:00", "+01:00")), dueForm(fields))
        assertEquals(ConcernFormResult.Invalid(ConcernFormError.OFFSET), dueForm(fields.copy(offset = "+03:00")))
        val early = (dueForm(fields.copy(offset = "+02:00")) as ConcernFormResult.Valid).action as ConcernAction.SetDue
        val later = (dueForm(fields.copy(offset = "+01:00")) as ConcernFormResult.Valid).action as ConcernAction.SetDue
        assertEquals(Instant.parse("2026-10-25T00:30:00Z").toEpochMilli(), early.atMs)
        assertEquals(3_600_000L, later.atMs!! - early.atMs!!)
        assertEquals("Europe/Berlin", early.zone)
        assertNull(early.sourceText)
    }

    @Test
    fun waitingNeedsAReasonAndAllowsOmittedFollowUpButNeverAHalfEnteredDate() {
        assertEquals(ConcernFormResult.Invalid(ConcernFormError.WAIT_REASON), waitForm(" ", "", ConcernTimeFields()))
        assertEquals(ConcernFormResult.Valid(ConcernAction.Wait(null, "Finance", null)), waitForm(" Finance ", " ", ConcernTimeFields()))
        assertEquals(ConcernFormResult.Invalid(ConcernFormError.DATE_TIME), waitForm("Finance", "", ConcernTimeFields("2026-10-09", "", "UTC")))
        assertEquals(ConcernFormResult.Invalid(ConcernFormError.PARTY_TOO_LONG), waitForm("a".repeat(201), "", ConcernTimeFields()))
        assertEquals(ConcernFormResult.Invalid(ConcernFormError.NOTE_TOO_LONG), waitForm("Finance", "a".repeat(501), ConcernTimeFields()))
    }

    @Test
    fun anAlreadyDueDateIsPermittedAndWaitingKeepsItsOwnInstant() {
        val fields = ConcernTimeFields("2020-10-07", "09:00", "Asia/Kolkata")
        val expected = Instant.parse("2020-10-07T03:30:00Z").toEpochMilli()
        assertEquals(ConcernFormResult.Valid(ConcernAction.SetDue(expected, "Asia/Kolkata", null)), dueForm(fields))
        assertEquals(ConcernFormResult.Valid(ConcernAction.Wait("Asked for receipt", "Finance", expected)), waitForm("Finance", "Asked for receipt", fields))
    }
}
