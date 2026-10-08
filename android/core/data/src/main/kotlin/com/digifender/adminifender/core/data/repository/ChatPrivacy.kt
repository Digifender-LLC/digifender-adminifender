package com.digifender.adminifender.core.data.repository

/**
 * What the phone knows about one synced email when it decides whether a stored answer may stay (FR-022, AT-17).
 * Only emails that are ready, not quarantined and permitted for models ([eligible]) may be the basis of an answer.
 */
data class SourceFact(val id: String, val quarantined: Boolean, val privacy: String, val state: String) {
    val eligible: Boolean get() = !quarantined && state == "ready" && (privacy == "hybrid" || privacy == "local_only")
}

/** The privacy values the Worker records for an email a model was given; anything else is not a valid recorded privacy. */
val RECORDED_PRIVACY: Set<String> = setOf("hybrid", "local_only")

/** Higher is stricter; a value this build does not know ranks strictest, so a new policy is never treated as looser. */
fun privacyRank(privacy: String): Int = when (privacy) {
    "hybrid" -> 0
    "local_only" -> 1
    "no_ai", "restricted_metadata" -> 2
    else -> 3
}

/** How the emails an answer rests on stand on this phone right now. */
enum class ContextCheck {
    /** Every one is on the phone, eligible and no stricter than when the model was given it. */
    HOLDS,

    /** At least one is not on the phone (yet, or any more), and none is known to be restricted. */
    MISSING,

    /** At least one is on the phone and ineligible, or stricter than when the model was given it. */
    RESTRICTED,
}

/**
 * Judges an answer against its whole context, not only the emails it cites: the answer text is derived from every email
 * the model was given. Restriction outranks absence, because a restricted email is a firm reason to hold back while an
 * absent one may simply not have synced yet.
 */
fun checkContext(context: List<ContextSource>, facts: Map<String, SourceFact>): ContextCheck {
    var missing = false
    for (source in context) {
        val fact = facts[source.sourceId]
        if (fact == null) {
            missing = true
        } else if (!fact.eligible || privacyRank(fact.privacy) > privacyRank(source.privacy)) {
            return ContextCheck.RESTRICTED
        }
    }
    return if (missing) ContextCheck.MISSING else ContextCheck.HOLDS
}

/**
 * Whether a synced change from [old] (null: not held) to [new] (null: deleted) can have made a stored answer unsafe.
 * This is only the cheap filter that decides which answers to look at again; [staleAnswers] makes the exact decision.
 * An answer is stored only while every email it rests on is held and eligible, so a change from a state that was
 * not held or not eligible cannot be the one that hurt it. Ordinary revisions and new mail are not restrictions.
 */
fun restricts(old: SourceFact?, new: SourceFact?): Boolean {
    if (old == null || !old.eligible) return false
    return new == null || !new.eligible || privacyRank(new.privacy) > privacyRank(old.privacy)
}

/**
 * The turns among [turns] whose stored answer rests on one of [changed] and no longer holds against [facts] (the emails
 * as they are now). Nothing else is touched: a question that is queued, in flight or without an answer holds no text to
 * protect, and an answer about other emails is none of this change's business. No clock or arrival time is involved.
 */
fun staleAnswers(changed: Set<String>, turns: List<ChatTurn>, facts: Map<String, SourceFact>): List<String> =
    turns.filter { turn ->
        val answer = turn.answer ?: return@filter false
        answer.context.any { it.sourceId in changed } && checkContext(answer.context, facts) != ContextCheck.HOLDS
    }.map { it.turnId }
