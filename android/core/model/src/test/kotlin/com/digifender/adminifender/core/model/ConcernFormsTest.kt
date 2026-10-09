package com.digifender.adminifender.core.model

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcernFormsTest {
    private val london = ZoneId.of("Europe/London")
    private val berlin = ZoneId.of("Europe/Berlin")
    private val now = Instant.parse("2026-10-08T10:00:00Z").toEpochMilli()
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun datesAndTimesAreStrict() {
        assertEquals("2026-10-08", parseDate(" 2026-10-08 ")?.toString())
        listOf("2026-02-30", "26-10-08", "2026-1-8", "+12345-01-01", "1999-12-31", "2100-01-01", "tomorrow", "").forEach { assertNull(it, parseDate(it)) }
        assertEquals("09:05", parseTime("9:05")?.toString())
        assertEquals("23:59", parseTime("23:59")?.toString())
        listOf("24:00", "12:60", "9", "9am", "12:00:30", "").forEach { assertNull(it, parseTime(it)) }
    }

    @Test
    fun dueDateIsTheExactInstantInThePhonesZone() {
        // 17:00 on 2026-10-05 in London is BST (UTC+1).
        val ready = buildDue("2026-10-05", "17:00", london, null) as FormOutcome.Ready
        assertEquals(ConcernAction.SetDue(ms("2026-10-05T16:00:00Z"), "Europe/London", null), ready.action)
        // The same wall time after the clocks go back is an hour later in UTC.
        val winter = buildDue("2026-10-26", "17:00", london, null) as FormOutcome.Ready
        assertEquals(ms("2026-10-26T17:00:00Z"), (winter.action as ConcernAction.SetDue).atMs)
    }

    @Test
    fun aDueDateInThePastIsAllowedBecauseItMakesTheItemOverdue() {
        assertTrue(buildDue("2020-01-01", "09:00", london, null) is FormOutcome.Ready)
    }

    @Test
    fun dueNeedsADateAndAReadableTime() {
        assertEquals(FormOutcome.Invalid(setOf(FormProblem.DATE_MISSING)), buildDue("", "09:00", london, null))
        assertEquals(FormOutcome.Invalid(setOf(FormProblem.BAD_DATE, FormProblem.BAD_TIME)), buildDue("soon", "later", london, null))
        assertEquals(FormOutcome.Invalid(setOf(FormProblem.BAD_TIME)), buildDue("2026-10-08", "", london, null))
    }

    @Test
    fun aTimeSkippedByTheClocksIsNeverGuessed() {
        // Berlin clocks jump from 02:00 to 03:00 on 2026-03-29.
        assertEquals(FormOutcome.Invalid(setOf(FormProblem.TIME_SKIPPED)), buildDue("2026-03-29", "02:30", berlin, null))
        assertEquals(FormOutcome.Invalid(setOf(FormProblem.TIME_SKIPPED)), buildDue("2026-03-29", "02:30", berlin, Fold.LATER))
        assertTrue(buildDue("2026-03-29", "03:30", berlin, null) is FormOutcome.Ready)
    }

    @Test
    fun aTimeThatHappensTwiceAsksWhichOneAndThenHonoursTheChoice() {
        // Berlin clocks go back from 03:00 to 02:00 on 2026-10-25, so 02:30 happens at 00:30Z and again at 01:30Z.
        val earlier = ms("2026-10-25T00:30:00Z")
        val later = ms("2026-10-25T01:30:00Z")
        assertEquals(FormOutcome.Invalid(setOf(FormProblem.TIME_REPEATS), earlier, later), buildDue("2026-10-25", "02:30", berlin, null))
        assertEquals(earlier, ((buildDue("2026-10-25", "02:30", berlin, Fold.EARLIER) as FormOutcome.Ready).action as ConcernAction.SetDue).atMs)
        assertEquals(later, ((buildDue("2026-10-25", "02:30", berlin, Fold.LATER) as FormOutcome.Ready).action as ConcernAction.SetDue).atMs)
        // A fold choice does not change a time that is not ambiguous.
        assertEquals(ms("2026-10-25T08:00:00Z"), ((buildDue("2026-10-25", "09:00", berlin, Fold.LATER) as FormOutcome.Ready).action as ConcernAction.SetDue).atMs)
    }

    @Test
    fun zoneNamesAreSentOnlyWhenTheWorkerWouldAcceptThem() {
        assertEquals("Europe/London", wireZone(london))
        assertEquals("UTC", wireZone(ZoneId.of("UTC")))
        assertNull(wireZone(ZoneOffset.ofHours(1)))
        assertNull(wireZone(ZoneOffset.UTC))
        assertNull(wireZone(ZoneId.of("GMT+01:00")))
        val fixed = buildDue("2026-10-08", "09:00", ZoneOffset.ofHours(2), null) as FormOutcome.Ready
        assertEquals(ConcernAction.SetDue(ms("2026-10-08T07:00:00Z"), null, null), fixed.action)
    }

    @Test
    fun waitWithNothingFilledInIsStillAWait() {
        assertEquals(FormOutcome.Ready(ConcernAction.Wait(null, null, null)), buildWait("  ", "", "", "09:00", london, null, now))
    }

    @Test
    fun waitTrimsTextAndKeepsTheFollowUpInstant() {
        val ready = buildWait(" Asked for terms ", " Landlord ", "2026-10-12", "09:00", london, null, now) as FormOutcome.Ready
        assertEquals(ConcernAction.Wait("Asked for terms", "Landlord", ms("2026-10-12T08:00:00Z")), ready.action)
    }

    @Test
    fun waitLimitsMatchTheWorker() {
        assertTrue(buildWait("x".repeat(WAIT_NOTE_MAX), "y".repeat(WAIT_PARTY_MAX), "", "", london, null, now) is FormOutcome.Ready)
        val tooLong = buildWait("x".repeat(WAIT_NOTE_MAX + 1), "y".repeat(WAIT_PARTY_MAX + 1), "", "", london, null, now) as FormOutcome.Invalid
        assertEquals(setOf(FormProblem.NOTE_TOO_LONG, FormProblem.PARTY_TOO_LONG), tooLong.problems)
    }

    @Test
    fun aFollowUpMustBeInTheFutureAndReadable() {
        assertEquals(FormOutcome.Invalid(setOf(FormProblem.FOLLOW_UP_IN_PAST)), buildWait("", "", "2026-10-08", "10:00", ZoneOffset.UTC, null, now))
        assertTrue(buildWait("", "", "2026-10-08", "10:01", ZoneOffset.UTC, null, now) is FormOutcome.Ready)
        assertEquals(FormOutcome.Invalid(setOf(FormProblem.BAD_DATE)), buildWait("", "", "next week", "09:00", london, null, now))
        assertEquals(FormOutcome.Invalid(setOf(FormProblem.BAD_TIME)), buildWait("", "", "2026-10-12", "9am", london, null, now))
    }

    @Test
    fun editingOnlyTheNoteKeepsAnOverdueFollowUpExactly() {
        // The follow-up passed three days ago and has seconds; the form shows it to the minute in the phone's zone.
        val overdue = ms("2026-10-05T14:00:30Z")
        val date = "2026-10-05"
        val time = "15:00"
        val kept = buildWait("Chased again", "Landlord", date, time, london, null, now, currentFollowUpMs = overdue) as FormOutcome.Ready
        assertEquals(ConcernAction.Wait("Chased again", "Landlord", overdue), kept.action)
        // Without the current value the same fields are a new past date and are refused, never silently dropped.
        assertEquals(FormOutcome.Invalid(setOf(FormProblem.FOLLOW_UP_IN_PAST)), buildWait("Chased again", "Landlord", date, time, london, null, now))
    }

    @Test
    fun aChangedOrClearedFollowUpIsAnExplicitChoice() {
        val overdue = ms("2026-10-05T14:00:30Z")
        // A different past date is newly specified, so the future rule applies to it.
        assertEquals(
            FormOutcome.Invalid(setOf(FormProblem.FOLLOW_UP_IN_PAST)),
            buildWait("", "", "2026-10-06", "15:00", london, null, now, currentFollowUpMs = overdue),
        )
        // A different time in the same minute window is not the current value either.
        assertEquals(
            FormOutcome.Invalid(setOf(FormProblem.FOLLOW_UP_IN_PAST)),
            buildWait("", "", "2026-10-05", "15:01", london, null, now, currentFollowUpMs = overdue),
        )
        val later = buildWait("", "", "2026-10-12", "09:00", london, null, now, currentFollowUpMs = overdue) as FormOutcome.Ready
        assertEquals(ms("2026-10-12T08:00:00Z"), (later.action as ConcernAction.Wait).followUpAtMs)
        // Emptying the date field is the owner removing the follow-up.
        val cleared = buildWait("", "", "", "15:00", london, null, now, currentFollowUpMs = overdue) as FormOutcome.Ready
        assertNull((cleared.action as ConcernAction.Wait).followUpAtMs)
    }

    @Test
    fun anUnchangedFollowUpOnARepeatedClockTimeIsKeptWithoutAskingAgain() {
        val stored = ms("2026-10-25T01:30:00Z")
        val kept = buildWait("", "", "2026-10-25", "02:30", berlin, null, ms("2026-10-26T00:00:00Z"), currentFollowUpMs = stored) as FormOutcome.Ready
        assertEquals(stored, (kept.action as ConcernAction.Wait).followUpAtMs)
        val other = ms("2026-10-20T01:30:00Z")
        val asks = buildWait("", "", "2026-10-25", "02:30", berlin, null, now, currentFollowUpMs = other) as FormOutcome.Invalid
        assertEquals(setOf(FormProblem.TIME_REPEATS), asks.problems)
    }

    @Test
    fun aFollowUpOnAnAmbiguousTimeAsksWhichOne() {
        val invalid = buildWait("", "", "2026-10-25", "02:30", berlin, null, now) as FormOutcome.Invalid
        assertEquals(setOf(FormProblem.TIME_REPEATS), invalid.problems)
        assertNotNull(invalid.earlierMs)
        val chosen = buildWait("", "", "2026-10-25", "02:30", berlin, Fold.LATER, now) as FormOutcome.Ready
        assertEquals(ms("2026-10-25T01:30:00Z"), (chosen.action as ConcernAction.Wait).followUpAtMs)
        assertEquals(FormOutcome.Invalid(setOf(FormProblem.TIME_SKIPPED)), buildWait("", "", "2026-03-29", "02:30", berlin, null, ms("2026-03-01T00:00:00Z")))
    }

    @Test
    fun clearingTheDueDateKeepsTheEmailsWordingAsAnUnconfirmedDate() {
        val concern = Concern(
            "c1", 3, "Council tax", Status.OPEN, Importance.IMPORTANT, null, null, null, null, null, null,
            Due(ms("2026-10-05T16:00:00Z"), "Europe/London", "by 5pm Monday", 1), emptyList(), 0,
        )
        val cleared = (applyOptimistic(concern, clearDue(), 10) as Transition.Applied).concern
        assertEquals(Due(null, null, "by 5pm Monday", 2), cleared.due)
        assertTrue(cleared.due!!.isUnconfirmed())
        assertFalse(concern.due!!.isUnconfirmed())
        assertEquals(3, cleared.revision)
    }

    @Test
    fun aDueEntryWithNothingToShowHasNoContent() {
        assertFalse(null.hasContent())
        assertFalse(Due(null, null, "  ", 1).hasContent())
        assertFalse(Due(null, null, null, 1).hasContent())
        assertTrue(Due(null, null, "Friday", 1).hasContent())
        assertTrue(Due(5, null, null, 1).hasContent())
    }
}
