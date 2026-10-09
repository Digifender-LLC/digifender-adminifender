package com.digifender.adminifender.core.model

/** Concern lifecycle mirrored from the backend (lld.md#concerns). Wire names match the API exactly. */
enum class Status(val wire: String) {
    OPEN("open"),
    WAITING("waiting"),
    SNOOZED("snoozed"),
    RESOLVED("resolved");

    companion object {
        fun fromWire(value: String): Status? = entries.firstOrNull { it.wire == value }
    }
}

enum class Importance(val wire: String) {
    ROUTINE("routine"),
    IMPORTANT("important"),
    URGENT("urgent"),
    CRITICAL("critical");

    companion object {
        fun fromWire(value: String): Importance? = entries.firstOrNull { it.wire == value }
    }
}

enum class ActionKind(val wire: String) {
    ACKNOWLEDGE("acknowledge"),
    RESOLVE("resolve"),
    REOPEN("reopen"),
    SNOOZE("snooze"),
    WAIT("wait"),
    SET_DUE("set_due"),
    MARK_READ("mark_read"),
}

data class Waiting(val note: String?, val awaitedParty: String?, val followUpAtMs: Long?)

data class Due(val atMs: Long?, val zone: String?, val sourceText: String?, val revision: Int)

/** The sync projection of one concern. Read, acknowledged and resolved are independent of importance. */
data class Concern(
    val id: String,
    val revision: Int,
    val title: String,
    val status: Status,
    val importance: Importance,
    val criticalBasis: String?,
    val readAtMs: Long?,
    val acknowledgedAtMs: Long?,
    val resolvedAtMs: Long?,
    val snoozeUntilMs: Long?,
    val waiting: Waiting?,
    val due: Due?,
    val sourceIds: List<String>,
    val updatedAtMs: Long,
    /** The catalog service the Worker linked this concern to, if any. Only used to scope a chat; no name is synced. */
    val serviceId: String? = null,
)

sealed interface ConcernAction {
    val kind: ActionKind

    data object Acknowledge : ConcernAction { override val kind = ActionKind.ACKNOWLEDGE }
    data object Resolve : ConcernAction { override val kind = ActionKind.RESOLVE }
    data object Reopen : ConcernAction { override val kind = ActionKind.REOPEN }
    data object MarkRead : ConcernAction { override val kind = ActionKind.MARK_READ }
    data class Snooze(val untilMs: Long) : ConcernAction { override val kind = ActionKind.SNOOZE }
    data class Wait(val note: String?, val awaitedParty: String?, val followUpAtMs: Long?) : ConcernAction { override val kind = ActionKind.WAIT }
    data class SetDue(val atMs: Long?, val zone: String?, val sourceText: String?) : ConcernAction { override val kind = ActionKind.SET_DUE }
}

/** Statuses each owner control is available from; identical to the backend table so offline controls match. */
private val ALLOWED: Map<ActionKind, Set<Status>> = mapOf(
    ActionKind.ACKNOWLEDGE to setOf(Status.OPEN, Status.WAITING, Status.SNOOZED),
    ActionKind.MARK_READ to setOf(Status.OPEN, Status.WAITING, Status.SNOOZED, Status.RESOLVED),
    ActionKind.RESOLVE to setOf(Status.OPEN, Status.WAITING, Status.SNOOZED),
    ActionKind.REOPEN to setOf(Status.WAITING, Status.SNOOZED, Status.RESOLVED),
    ActionKind.SNOOZE to setOf(Status.OPEN, Status.WAITING, Status.SNOOZED),
    ActionKind.WAIT to setOf(Status.OPEN, Status.WAITING),
    ActionKind.SET_DUE to setOf(Status.OPEN, Status.WAITING, Status.SNOOZED, Status.RESOLVED),
)

/** Controls shown with a concern, in the backend's order, available without a model or network. */
fun availableActions(status: Status): List<ActionKind> = ActionKind.entries.filter { status in ALLOWED.getValue(it) }

sealed interface Transition {
    data class Applied(val concern: Concern) : Transition
    data class NotAvailable(val status: Status, val kind: ActionKind) : Transition
    data object SnoozeInPast : Transition
}

/**
 * The optimistic local result of an owner action, shown as pending until the server confirms it. The revision is
 * unchanged: only the server assigns revisions, and the queued mutation carries the revision it expects.
 */
fun applyOptimistic(concern: Concern, action: ConcernAction, nowMs: Long): Transition {
    if (concern.status !in ALLOWED.getValue(action.kind)) return Transition.NotAvailable(concern.status, action.kind)
    val next = when (action) {
        ConcernAction.Acknowledge -> concern.copy(acknowledgedAtMs = nowMs)
        ConcernAction.MarkRead -> concern.copy(readAtMs = nowMs)
        ConcernAction.Resolve -> concern.copy(status = Status.RESOLVED, resolvedAtMs = nowMs, snoozeUntilMs = null)
        ConcernAction.Reopen -> concern.copy(status = Status.OPEN, resolvedAtMs = null, snoozeUntilMs = null)
        is ConcernAction.Snooze -> {
            if (action.untilMs <= nowMs) return Transition.SnoozeInPast
            concern.copy(status = Status.SNOOZED, snoozeUntilMs = action.untilMs)
        }
        is ConcernAction.Wait -> concern.copy(status = Status.WAITING, waiting = Waiting(action.note, action.awaitedParty, action.followUpAtMs))
        is ConcernAction.SetDue -> concern.copy(
            due = Due(
                atMs = action.atMs,
                zone = action.zone,
                sourceText = action.sourceText ?: concern.due?.sourceText,
                revision = (concern.due?.revision ?: 0) + 1,
            ),
        )
    }
    return Transition.Applied(next.copy(updatedAtMs = nowMs))
}
