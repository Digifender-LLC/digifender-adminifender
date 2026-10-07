package com.digifender.adminifender.core.data.repository

import com.digifender.adminifender.core.data.network.BriefingApi
import com.digifender.adminifender.core.data.network.BriefingDto
import com.digifender.adminifender.core.data.network.BriefingReviewResult
import com.digifender.adminifender.core.data.network.ConcernDto
import com.digifender.adminifender.core.data.network.ReadResult
import com.digifender.adminifender.core.data.network.isSourceId
import com.digifender.adminifender.core.data.network.toDomain
import com.digifender.adminifender.core.model.Concern
import java.time.DateTimeException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Immutable server snapshot; never overlaid with later concern edits when marking it reviewed. */
data class Briefing(
    val id: String,
    val snapshotSeq: Long,
    val throughTimeMs: Long,
    val lastReviewedAtMs: Long?,
    val newConcerns: List<Concern>,
    val changedConcerns: List<Concern>,
    val overdue: List<Concern>,
    val unacknowledged: List<Concern>,
    val newSourceIds: List<String>,
    val hasMore: Boolean,
)

/** The request identity is allocated once, when the snapshot is loaded, and retained across explicit retries. */
data class BriefingSnapshot(val briefing: Briefing, val reviewKey: String)
data class VisibleBriefing(val snapshot: BriefingSnapshot, val hiddenConcerns: Boolean)

class BriefingRepository(private val api: BriefingApi, private val cache: CacheSource, private val scheduler: SyncScheduler? = null, private val newKey: () -> String = { UUID.randomUUID().toString() }) {
    suspend fun load(): ReadResult<BriefingSnapshot> {
        scheduler?.requestNow()
        return when (val result = api.current()) {
            is ReadResult.Ok -> try {
                ReadResult.Ok(BriefingSnapshot(result.value.toBriefing(), newKey()))
            } catch (_: IllegalArgumentException) {
                ReadResult.Unavailable("malformed_response")
            } catch (_: DateTimeException) {
                ReadResult.Unavailable("malformed_response")
            }
            ReadResult.SessionExpired -> ReadResult.SessionExpired
            ReadResult.CursorExpired -> ReadResult.Unavailable("unexpected")
            is ReadResult.Unavailable -> result
        }
    }

    /**
     * A cached deletion or source detachment must hide snapshot text too. Missing Room rows are hidden from
     * the first emission, covering deletions while HTTP is in flight. A new matching row may appear after sync;
     * once an observed row is removed, a late cache update cannot resurrect that old text.
     */
    fun visible(snapshot: BriefingSnapshot): Flow<VisibleBriefing> {
        val seen = mutableSetOf<String>()
        val hidden = mutableSetOf<String>()
        return cache.concerns().map { current ->
            val byId = current.associateBy { it.id }
            val b = snapshot.briefing
            val all = b.newConcerns + b.changedConcerns + b.overdue + b.unacknowledged
            all.forEach { original ->
                val cached = byId[original.id]
                if (cached != null) {
                    seen += original.id
                    if (cached.revision >= original.revision && (cached.title != original.title || cached.sourceIds.toSet() != original.sourceIds.toSet())) hidden += original.id
                } else if (original.id in seen) hidden += original.id
            }
            fun safe(original: Concern): Boolean {
                val cached = byId[original.id] ?: return false
                return original.id !in hidden && cached.revision == original.revision && cached.title == original.title && cached.sourceIds.toSet() == original.sourceIds.toSet()
            }
            val unavailable = all.any { !safe(it) }
            fun List<Concern>.retained() = filter(::safe)
            VisibleBriefing(snapshot.copy(briefing = b.copy(newConcerns = b.newConcerns.retained(), changedConcerns = b.changedConcerns.retained(), overdue = b.overdue.retained(), unacknowledged = b.unacknowledged.retained())), unavailable)
        }
    }

    suspend fun review(snapshot: BriefingSnapshot): BriefingReviewResult {
        val result = api.review(snapshot.briefing.id, snapshot.reviewKey)
        if (result is BriefingReviewResult.Reviewed) {
            val receipt = result.receipt
            if (receipt.id != snapshot.briefing.id || receipt.reviewedThroughSeq != snapshot.briefing.snapshotSeq) return BriefingReviewResult.NoAnswer
            try { Instant.parse(receipt.reviewedAt) } catch (_: DateTimeException) { return BriefingReviewResult.NoAnswer }
        }
        return result
    }
}

private fun BriefingDto.toBriefing(): Briefing {
    require(isSourceId(id) && snapshotSeq >= 0 && newSourceIds.size <= 100 && newSourceIds.distinct().size == newSourceIds.size && newSourceIds.all(::isSourceId))
    fun List<ConcernDto>.parsed(): List<Concern> {
        require(size <= 100 && map { it.id }.distinct().size == size)
        return map { require(isSourceId(it.id) && it.revision > 0); requireNotNull(it.toDomain()) }
    }
    return Briefing(id, snapshotSeq, Instant.parse(throughTime).toEpochMilli(), lastReviewedAt?.let { Instant.parse(it).toEpochMilli() }, newConcerns.parsed(), changedConcerns.parsed(), overdue.parsed(), unacknowledged.parsed(), newSourceIds, hasMore)
}
