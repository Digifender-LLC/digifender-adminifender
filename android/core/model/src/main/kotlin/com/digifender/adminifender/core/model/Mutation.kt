package com.digifender.adminifender.core.model

/**
 * One owner change queued while offline (lld.md#sync). The idempotency key is fixed when the change is made, so
 * every resend is the same request and the server applies it at most once.
 */
data class PendingMutation(
    val idempotencyKey: String,
    val concernId: String,
    val expectedRevision: Int,
    val action: ConcernAction,
    val createdAtMs: Long,
    val attempts: Int = 0,
)

/** What the server said about one send, reduced to the cases the outbox handles differently. */
sealed interface SendResult {
    data class Accepted(val revision: Int) : SendResult
    data class StaleRevision(val currentRevision: Int?) : SendResult
    data class Rejected(val code: String) : SendResult
    /** No authoritative answer: network loss, timeout or a retryable server error. */
    data object NoAnswer : SendResult
    data object SessionExpired : SendResult
}

sealed interface OutboxDecision {
    /** Done: drop the record; the change feed delivers the confirmed projection. */
    data object Remove : OutboxDecision

    /** Resend later with the same key. */
    data class Retry(val next: PendingMutation, val delayMs: Long) : OutboxDecision

    /** A newer server decision exists. Keep the owner's change visible as a conflict for explicit reapplication. */
    data class Conflict(val currentRevision: Int?) : OutboxDecision

    /** Refused for good (for example the action is no longer available). Show it; never retry it. */
    data class Failed(val code: String) : OutboxDecision

    /** Stop sending until the owner signs in again; the record stays queued. */
    data object HoldForSignIn : OutboxDecision
}

private const val BASE_DELAY_MS = 30_000L
private const val MAX_DELAY_MS = 60 * 60 * 1000L

fun retryDelayMs(attempts: Int): Long = minOf(BASE_DELAY_MS shl minOf(maxOf(attempts - 1, 0), 20), MAX_DELAY_MS)

/** Outbox handling for one response. A stale offline change never overwrites a newer decision. */
fun decide(mutation: PendingMutation, result: SendResult): OutboxDecision = when (result) {
    is SendResult.Accepted -> OutboxDecision.Remove
    is SendResult.StaleRevision -> OutboxDecision.Conflict(result.currentRevision)
    is SendResult.Rejected -> OutboxDecision.Failed(result.code)
    SendResult.SessionExpired -> OutboxDecision.HoldForSignIn
    SendResult.NoAnswer -> {
        val next = mutation.copy(attempts = mutation.attempts + 1)
        OutboxDecision.Retry(next, retryDelayMs(next.attempts))
    }
}

/**
 * Applies a server projection from the change feed. The server copy always wins for stored state; a queued change
 * whose expected revision is now behind is flagged as a conflict rather than silently reapplied.
 */
fun conflictsWith(mutation: PendingMutation, server: Concern): Boolean = server.id == mutation.concernId && server.revision > mutation.expectedRevision
