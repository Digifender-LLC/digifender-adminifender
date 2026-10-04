package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.ChangesPageDto
import com.digifender.adminifender.core.data.network.SyncChange
import com.digifender.adminifender.core.data.network.toChange
import com.digifender.adminifender.core.data.network.wireJson
import com.digifender.adminifender.core.model.Due
import com.digifender.adminifender.core.model.Importance
import com.digifender.adminifender.core.model.Status
import com.digifender.adminifender.core.model.Waiting
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DtoTest {
    private fun ms(text: String) = Instant.parse(text).toEpochMilli()

    @Test
    fun parsesTheSharedSyncFixture() {
        val page = wireJson.decodeFromString(ChangesPageDto.serializer(), contractFile("fixtures/v1/sync-changes/valid/concern-source-tombstone.json"))
        val changes = page.changes.map { it.toChange() }
        val source = (changes[0] as SyncChange.SourceChanged).source
        assertEquals("Invoice 42", source.subject)
        assertEquals(listOf("billing@vendor.example"), source.from)
        assertEquals(false, source.quarantined)
        assertEquals(ms("2026-10-03T12:00:00.000Z"), source.receivedAtMs)

        val concern = (changes[1] as SyncChange.ConcernChanged).concern
        assertEquals(Status.WAITING, concern.status)
        assertEquals(Importance.IMPORTANT, concern.importance)
        assertEquals(Waiting("Asked for a corrected invoice", "Vendor billing", ms("2026-10-06T09:00:00.000Z")), concern.waiting)
        assertEquals(Due(ms("2026-10-10T17:00:00.000Z"), "Europe/Berlin", "by Friday next week", 1), concern.due)
        assertEquals(ms("2026-10-03T12:05:00.000Z"), concern.readAtMs)

        assertEquals(SyncChange.Ignored(43), changes[2])
        assertEquals(SyncChange.Removed(44, "concern", "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"), changes[3])
        assertEquals(44L, page.throughSeq)
    }

    @Test
    fun refusesAProjectionWithAnUnknownStatus() {
        val page = wireJson.decodeFromString(ChangesPageDto.serializer(), contractFile("fixtures/v1/sync-changes/invalid/unknown-status.json"))
        assertThrows(IllegalArgumentException::class.java) { page.changes.single().toChange() }
    }
}
