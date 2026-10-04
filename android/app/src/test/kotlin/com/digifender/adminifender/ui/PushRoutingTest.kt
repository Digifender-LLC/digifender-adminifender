package com.digifender.adminifender.ui

import com.digifender.adminifender.push.notificationSpec
import com.digifender.adminifender.validDestination
import com.digifender.adminifender.core.model.PushEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushRoutingTest {
    private val event = "6f1c2b3a-4d5e-4f60-8a1b-2c3d4e5f6a7b"
    private val concern = "0b4e6f8a-2c3d-4e5f-8a9b-1c2d3e4f5a6b"

    private fun parse(kind: String, destination: String) = PushEvent.parse(mapOf("eventId" to event, "kind" to kind, "destinationId" to destination))!!

    @Test
    fun criticalUsesItsOwnHighPriorityChannel() {
        val spec = notificationSpec(parse("critical", concern))
        assertEquals("critical", spec.channelId)
        assertEquals(true, spec.highPriority)
        assertEquals(concern, spec.destination)
    }

    @Test
    fun remindersShareTheReviewChannelAndInactivityOpensTheBriefing() {
        assertEquals("review_reminders", notificationSpec(parse("due", concern)).channelId)
        val inactivity = notificationSpec(parse("inactivity", "briefing"))
        assertEquals("review_reminders", inactivity.channelId)
        assertEquals("briefing", inactivity.destination)
        assertEquals(false, inactivity.highPriority)
    }

    @Test
    fun aRedeliveredEventReplacesItsNotification() {
        assertEquals(notificationSpec(parse("due", concern)).notificationId, notificationSpec(parse("due", concern)).notificationId)
    }

    @Test
    fun onlyIdentifiersPassFromANotificationTap() {
        assertEquals(concern, validDestination(concern))
        assertEquals("briefing", validDestination("briefing"))
        assertNull(validDestination(null))
        assertNull(validDestination("resolve:$concern"))
        assertNull(validDestination("../../concerns"))
        assertNull(validDestination(concern.uppercase()))
    }
}
