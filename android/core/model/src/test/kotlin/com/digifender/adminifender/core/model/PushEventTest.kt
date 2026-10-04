package com.digifender.adminifender.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushEventTest {
    private val event = "11111111-1111-4111-8111-111111111111"
    private val concern = "22222222-2222-4222-8222-222222222222"

    @Test
    fun parsesOnlyOpaqueIdentity() {
        assertEquals(
            PushEvent(event, NotificationKind.CRITICAL, PushEvent.Destination.Concern(concern)),
            PushEvent.parse(mapOf("eventId" to event, "kind" to "critical", "destinationId" to concern)),
        )
        assertEquals(
            PushEvent(event, NotificationKind.INACTIVITY, PushEvent.Destination.Briefing),
            PushEvent.parse(mapOf("eventId" to event, "kind" to "inactivity", "destinationId" to "briefing")),
        )
        assertEquals(NotificationChannelKind.REVIEW_REMINDERS, channelFor(NotificationKind.DUE))
        assertEquals(NotificationChannelKind.CRITICAL, channelFor(NotificationKind.CRITICAL))
    }

    @Test
    fun rejectsMalformedOrMismatchedPayloads() {
        assertNull(PushEvent.parse(mapOf("eventId" to "not-a-uuid", "kind" to "critical", "destinationId" to concern)))
        assertNull(PushEvent.parse(mapOf("eventId" to event, "kind" to "execute", "destinationId" to concern)))
        assertNull(PushEvent.parse(mapOf("eventId" to event, "kind" to "critical", "destinationId" to "https://example.com")))
        assertNull(PushEvent.parse(mapOf("eventId" to event, "kind" to "critical", "destinationId" to "briefing")))
        assertNull(PushEvent.parse(mapOf("eventId" to event, "kind" to "inactivity", "destinationId" to concern)))
    }
}
