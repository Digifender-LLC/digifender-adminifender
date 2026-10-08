package com.digifender.adminifender.core.data.local

import androidx.room.withTransaction
import com.digifender.adminifender.core.data.repository.Briefing
import com.digifender.adminifender.core.data.repository.BriefingCache
import com.digifender.adminifender.core.data.repository.BriefingItemsCodec
import com.digifender.adminifender.core.data.repository.BriefingRecord
import com.digifender.adminifender.core.data.repository.ReviewPhase
import com.digifender.adminifender.core.data.repository.StoredReview
import kotlinx.coroutines.flow.map

private fun BriefingEntity.toRecord(): BriefingRecord? = try {
    val base = Briefing(briefingId, snapshotSeq, throughTime, lastReviewedAt, fetchedAt, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    val phase = ReviewPhase.valueOf(reviewPhase)
    BriefingRecord(BriefingItemsCodec.decode(itemsJson, base), StoredReview(phase, reviewKey, reviewedAt))
} catch (_: IllegalArgumentException) {
    // A damaged row is treated as no briefing; the next refresh replaces it.
    null
}

/** Room-backed [BriefingCache]: the briefing row is replaced in one transaction and published from there. */
class RoomBriefingCache(private val db: AppDatabase) : BriefingCache {
    override fun current() = db.briefing().observe().map { it?.toRecord() }

    override suspend fun replace(briefing: Briefing) = db.withTransaction {
        val old = db.briefing().get()
        // The same unreviewed snapshot can be served again; its pending review intent must survive the refresh.
        val keep = old?.takeIf { it.briefingId == briefing.id }
        db.briefing().clear()
        db.briefing().put(
            BriefingEntity(
                briefingId = briefing.id,
                snapshotSeq = briefing.snapshotSeq,
                throughTime = briefing.throughTimeMs,
                lastReviewedAt = briefing.lastReviewedAtMs,
                fetchedAt = briefing.fetchedAtMs,
                itemsJson = BriefingItemsCodec.encode(briefing),
                reviewPhase = keep?.reviewPhase ?: ReviewPhase.NONE.name,
                reviewKey = keep?.reviewKey,
                reviewedAt = keep?.reviewedAt,
            ),
        )
    }

    override suspend fun setReview(briefingId: String, review: StoredReview): Boolean =
        db.briefing().setReview(briefingId, review.phase.name, review.key, review.reviewedAtMs) > 0
}
