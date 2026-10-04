package com.digifender.adminifender.ui

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormsTest {
    private val zone = ZoneId.of("America/New_York")
    private val now = ZonedDateTime.of(2026, 10, 30, 22, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun blankWaitFieldsBecomeNullAndTextIsCutToServerLimits() {
        val a = waitAction("  ", "x".repeat(WAIT_NOTE_MAX + 50), null, now, zone)
        assertNull(a.awaitedParty)
        assertEquals(WAIT_NOTE_MAX, a.note!!.length)
        assertNull(a.followUpAtMs)
    }

    @Test
    fun followUpIsNineInTheMorningLocalAcrossTheClockChange() {
        // 2026-11-01 is the US fall-back day; 7 days from Oct 30 is Nov 6, still 09:00 local.
        val a = waitAction("Bank", "", 7, now, zone)
        assertEquals(ZonedDateTime.of(2026, 11, 6, 9, 0, 0, 0, zone).toInstant().toEpochMilli(), a.followUpAtMs)
        assertEquals("Bank", a.awaitedParty)
    }

    @Test
    fun dueDatesCarryTheirZoneAndClearingSendsNulls() {
        val set = dueAction(LocalDate.of(2026, 11, 2), zone)
        assertEquals(ZonedDateTime.of(2026, 11, 2, 17, 0, 0, 0, zone).toInstant().toEpochMilli(), set.atMs)
        assertEquals("America/New_York", set.zone)
        val clear = dueAction(null, zone)
        assertNull(clear.atMs)
        assertNull(clear.zone)
    }

    @Test
    fun pickerMillisAreReadAsAUtcDay() {
        assertEquals(LocalDate.of(2026, 11, 2), pickedDate(LocalDate.of(2026, 11, 2).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()))
    }
}
