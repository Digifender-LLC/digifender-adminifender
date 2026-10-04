package com.digifender.adminifender.ui

import com.digifender.adminifender.core.data.repository.Freshness
import com.digifender.adminifender.core.model.ActionKind
import com.digifender.adminifender.core.model.Status
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class LabelsTest {
    private val now = 1_800_000_000_000L
    private fun fresh(last: Long?) = Freshness(last, needsFullDownload = last == null, queued = 0, needsOwner = 0, held = false)

    @Test
    fun freshnessNeverClaimsASyncThatDidNotHappen() {
        assertEquals(FreshLine.NeverSynced, freshLine(fresh(null), now))
        assertEquals(FreshLine.JustNow, freshLine(fresh(now - 30_000), now))
        assertEquals(FreshLine.Minutes(5), freshLine(fresh(now - 5 * 60_000), now))
        assertEquals(FreshLine.Hours(3), freshLine(fresh(now - 3 * 3_600_000 - 1), now))
        assertEquals(FreshLine.Days(3), freshLine(fresh(now - 3 * 86_400_000L), now))
        // A clock that moved backwards reads as just now, not as the future.
        assertEquals(FreshLine.JustNow, freshLine(fresh(now + 60_000), now))
    }

    @Test
    fun quickActionsFollowTheSharedControlTable() {
        assertEquals(listOf(ActionKind.ACKNOWLEDGE, ActionKind.RESOLVE, ActionKind.SNOOZE), quickActions(Status.OPEN))
        assertEquals(listOf(ActionKind.REOPEN), quickActions(Status.RESOLVED))
    }

    @Test
    fun tomorrowMorningIsNineLocalTimeAcrossADstChange() {
        val zone = ZoneId.of("Europe/Berlin")
        // Saturday 2026-03-28 22:00 local; clocks spring forward that night.
        val evening = ZonedDateTime.of(2026, 3, 28, 22, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(ZonedDateTime.of(2026, 3, 29, 9, 0, 0, 0, zone).toInstant().toEpochMilli(), tomorrowMorning(evening, zone))
    }
}
