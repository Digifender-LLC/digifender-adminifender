package com.digifender.adminifender.core.model

/** Push event kinds from the backend payload `{eventId, kind, destinationId}` (lld.md#notifications). */
enum class NotificationKind(val wire: String) {
    CRITICAL("critical"),
    DUE("due"),
    INACTIVITY("inactivity");

    companion object {
        fun fromWire(value: String): NotificationKind? = entries.firstOrNull { it.wire == value }
    }
}

/** The three Android notification channels (tdd.md#android). */
enum class NotificationChannelKind(val id: String) {
    CRITICAL("critical"),
    REVIEW_REMINDERS("review_reminders"),
    PROCESSING("processing"),
}

fun channelFor(kind: NotificationKind): NotificationChannelKind = when (kind) {
    NotificationKind.CRITICAL -> NotificationChannelKind.CRITICAL
    NotificationKind.DUE, NotificationKind.INACTIVITY -> NotificationChannelKind.REVIEW_REMINDERS
}

/**
 * A received push, validated. Unknown kinds and malformed IDs are ignored; nothing in a push can run an action.
 * The app shows generic text and opens the destination, where it fetches current state.
 */
data class PushEvent(val eventId: String, val kind: NotificationKind, val destination: Destination) {
    sealed interface Destination {
        data class Concern(val id: String) : Destination
        data object Briefing : Destination
    }

    companion object {
        private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

        fun parse(data: Map<String, String>): PushEvent? {
            val eventId = data["eventId"]?.takeIf { UUID.matches(it) } ?: return null
            val kind = data["kind"]?.let(NotificationKind::fromWire) ?: return null
            val destinationId = data["destinationId"] ?: return null
            val destination = when {
                destinationId == "briefing" -> Destination.Briefing
                UUID.matches(destinationId) -> Destination.Concern(destinationId)
                else -> return null
            }
            if ((kind == NotificationKind.INACTIVITY) != (destination == Destination.Briefing)) return null
            return PushEvent(eventId, kind, destination)
        }
    }
}
