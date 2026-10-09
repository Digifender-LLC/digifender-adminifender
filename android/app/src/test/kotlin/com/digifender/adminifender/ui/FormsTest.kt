package com.digifender.adminifender.ui

import com.digifender.adminifender.core.model.ActionKind
import com.digifender.adminifender.core.model.Due
import com.digifender.adminifender.core.model.ConcernAction
import com.digifender.adminifender.core.model.FormOutcome
import com.digifender.adminifender.core.model.Status
import com.digifender.adminifender.core.model.Waiting
import com.digifender.adminifender.core.model.buildWait
import com.digifender.adminifender.core.model.parseDate
import com.digifender.adminifender.core.model.parseTime
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormsTest {
    private val london = ZoneId.of("Europe/London")
    private val newYork = ZoneId.of("America/New_York")
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun formsOfferWaitAndDueDateOnlyWhereTheSharedTableAllowsThem() {
        assertEquals(listOf(ActionKind.WAIT, ActionKind.SET_DUE), formActions(Status.OPEN))
        assertEquals(listOf(ActionKind.WAIT, ActionKind.SET_DUE), formActions(Status.WAITING))
        assertEquals(listOf(ActionKind.SET_DUE), formActions(Status.SNOOZED))
        assertEquals(listOf(ActionKind.SET_DUE), formActions(Status.RESOLVED))
    }

    @Test
    fun anExistingDateStartsTheFormInTheEditingZoneAndReadsBackExactly() {
        val (date, time) = formFields(ms("2026-10-05T16:00:00Z"), london)
        assertEquals("2026-10-05" to "17:00", date to time)
        assertNotNull(parseDate(date))
        assertNotNull(parseTime(time))
        // Single-digit hours and minutes are zero padded.
        assertEquals("2026-10-05" to "04:05", formFields(ms("2026-10-05T03:05:00Z"), london))
    }

    @Test
    fun theWaitFormStartsWithAnOverdueFollowUpAndSavingTheNoteKeepsIt() {
        val now = ms("2026-10-08T10:00:00Z")
        val overdue = ms("2026-10-05T14:00:30Z")
        val waiting = Waiting("Asked for terms", "Landlord", overdue)
        val (date, time) = waitStartFields(waiting, london)!!
        assertEquals("2026-10-05" to "15:00", date to time)
        val saved = buildWait("Chased on Monday", "Landlord", date, time, london, null, now, currentFollowUpMs = waiting.followUpAtMs) as FormOutcome.Ready
        assertEquals(ConcernAction.Wait("Chased on Monday", "Landlord", overdue), saved.action)
        assertNull(waitStartFields(Waiting("n", null, null), london))
        assertNull(waitStartFields(null, london))
    }

    @Test
    fun formTextStaysAsciiWhateverTheDefaultLanguageIs() {
        val saved = Locale.getDefault()
        try {
            // Arabic-Indic digits are forced with a Unicode extension so the precondition holds on every JDK.
            val arabic = Locale.forLanguageTag("ar-u-nu-arab")
            Locale.setDefault(arabic)
            assertFalse("precondition: the default locale formats digits non-ASCII", String.format("%02d:%02d", 9, 5).all { it.code < 128 })
            val (date, time) = formFields(ms("2026-10-05T08:05:00Z"), ZoneId.of("UTC"))
            assertEquals("2026-10-05" to "08:05", date to time)
            assertEquals("08:05", parseTime(time)?.toString())
            assertNotNull(parseDate(datePreset(ms("2026-10-05T08:05:00Z"), ZoneId.of("UTC"), 7)))
            assertEquals("2026-10-12", datePreset(ms("2026-10-05T08:05:00Z"), ZoneId.of("UTC"), 7))
            // Display text is still localized: it is not the machine-editable form.
            assertFalse(formatInstant(ms("2026-10-05T08:05:00Z"), ZoneId.of("UTC"), arabic).all { it.code < 128 })
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun presetsFollowTheDayInTheZoneNotInUtc() {
        // 23:30 UTC on 5 October is already the 6th in Berlin but still the 5th in New York.
        val now = ms("2026-10-05T23:30:00Z")
        assertEquals("2026-10-06", datePreset(now, ZoneId.of("Europe/Berlin"), 0))
        assertEquals("2026-10-05", datePreset(now, newYork, 0))
        assertEquals("2026-10-06", datePreset(now, newYork, 1))
    }

    @Test
    fun aDueDateIsShownInItsOwnZoneAndNamesThatZoneOnlyWhenTheClockDiffers() {
        val at = ms("2026-10-05T21:00:00Z")
        val sameClock = dueDisplay(Due(at, "Europe/London", null, 1), london)!!
        assertEquals(london, sameClock.zone)
        assertFalse(sameClock.showZone)
        val travelling = dueDisplay(Due(at, "Europe/London", null, 1), newYork)!!
        assertTrue(travelling.showZone)
        assertEquals(london, travelling.zone)
        // No zone from the email: this phone's zone, never named.
        val noZone = dueDisplay(Due(at, null, null, 1), newYork)!!
        assertEquals(newYork, noZone.zone)
        assertFalse(noZone.showZone)
        // A zone the phone does not know falls back to its own rather than failing.
        assertEquals(newYork, dueDisplay(Due(at, "Not/AZone", null, 1), newYork)!!.zone)
    }

    @Test
    fun zonesWithEqualClocksAtThatMomentAreNotNamed() {
        // Both are on UTC+1 in winter, so naming the zone would only add noise.
        val winter = ms("2026-12-05T12:00:00Z")
        assertFalse(dueDisplay(Due(winter, "Europe/Berlin", null, 1), ZoneId.of("Europe/Paris"))!!.showZone)
        // In summer London (UTC+1) and Berlin (UTC+2) differ, so it is named.
        assertTrue(dueDisplay(Due(ms("2026-07-05T12:00:00Z"), "Europe/Berlin", null, 1), london)!!.showZone)
    }

    @Test
    fun wordingWithoutADateIsUnconfirmedAndNothingIsShownForAnEmptyEntry() {
        val unconfirmed = dueDisplay(Due(null, null, " by Friday ", 0), london)!!
        assertNull(unconfirmed.atMs)
        assertEquals("by Friday", unconfirmed.unconfirmedText)
        assertNull(dueDisplay(Due(null, null, "  ", 1), london))
        assertNull(dueDisplay(null, london))
        // A confirmed date never also shows the wording as unconfirmed.
        assertNull(dueDisplay(Due(5, null, "Friday", 1), london)!!.unconfirmedText)
    }
}
