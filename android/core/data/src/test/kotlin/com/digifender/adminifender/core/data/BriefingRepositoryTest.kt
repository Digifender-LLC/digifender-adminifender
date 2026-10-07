package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.BriefingApi
import com.digifender.adminifender.core.data.network.BriefingDto
import com.digifender.adminifender.core.data.network.BriefingReviewDto
import com.digifender.adminifender.core.data.network.BriefingReviewResult
import com.digifender.adminifender.core.data.network.ConcernDto
import com.digifender.adminifender.core.data.network.ReadResult
import com.digifender.adminifender.core.data.network.toDomain
import com.digifender.adminifender.core.data.repository.BriefingRepository
import com.digifender.adminifender.core.data.repository.BriefingSnapshot
import com.digifender.adminifender.core.data.repository.CacheSource
import com.digifender.adminifender.core.data.repository.VisibleBriefing
import com.digifender.adminifender.core.data.sync.QueuedMutation
import com.digifender.adminifender.core.model.Concern
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BRIEFING_ID = "00000000-0000-4000-8000-000000000001"
private const val CONCERN_ID = "00000000-0000-4000-8000-000000000002"
private const val SOURCE_ID = "00000000-0000-4000-8000-000000000003"
private const val WHEN = "2026-10-07T09:00:00Z"
private fun card() = ConcernDto(CONCERN_ID, 1, "Synthetic invoice", "open", "important", sourceIds = listOf(SOURCE_ID), updatedAt = WHEN)
private fun briefing() = BriefingDto(BRIEFING_ID, 8, WHEN, newConcerns = listOf(card()), changedConcerns = emptyList(), overdue = emptyList(), unacknowledged = listOf(card()), newSourceIds = listOf(SOURCE_ID))

private class BriefingFakeApi : BriefingApi {
    var loaded = briefing()
    var reviewResult: BriefingReviewResult = BriefingReviewResult.NoAnswer
    val requests = mutableListOf<Pair<String, String>>()
    override suspend fun current() = ReadResult.Ok(loaded)
    override suspend fun review(id: String, idempotencyKey: String): BriefingReviewResult {
        requests += id to idempotencyKey
        return reviewResult
    }
}

private class BriefingCache : CacheSource {
    val cards = MutableStateFlow(listOf(card().toDomain()!!))
    override fun concerns() = cards
    override fun mutations() = MutableStateFlow(emptyList<QueuedMutation>())
    override fun syncState() = MutableStateFlow<Pair<Long?, Boolean>>(null to false)
}

@OptIn(ExperimentalCoroutinesApi::class)
class BriefingRepositoryTest {
    private val api = BriefingFakeApi()
    private val cache = BriefingCache()
    private val repo = BriefingRepository(api, cache) { "same-key" }

    @Test
    fun reviewRetriesTheDisplayedSnapshotWithTheSameKeyAndChecksReceiptIdentity() = runTest {
        val snapshot = (repo.load() as ReadResult.Ok).value
        api.loaded = briefing().copy(snapshotSeq = 15)
        assertEquals(BriefingReviewResult.NoAnswer, repo.review(snapshot))
        api.reviewResult = BriefingReviewResult.Reviewed(BriefingReviewDto(BRIEFING_ID, 15, WHEN))
        assertEquals(BriefingReviewResult.NoAnswer, repo.review(snapshot))
        api.reviewResult = BriefingReviewResult.Reviewed(BriefingReviewDto(BRIEFING_ID, 8, WHEN))
        assertTrue(repo.review(snapshot) is BriefingReviewResult.Reviewed)
        assertEquals(List(3) { BRIEFING_ID to "same-key" }, api.requests)
    }

    @Test
    fun cachedDeletionAndDetachmentHideOldSnapshotTextPermanently() = runTest {
        val snapshot = (repo.load() as ReadResult.Ok<BriefingSnapshot>).value
        var visible: VisibleBriefing? = null
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.visible(snapshot).collect { visible = it } }
        assertEquals(1, visible!!.snapshot.briefing.newConcerns.size)
        cache.cards.value = emptyList()
        assertTrue(visible!!.hiddenConcerns)
        assertEquals(emptyList<Concern>(), visible!!.snapshot.briefing.newConcerns)
        cache.cards.value = listOf(card().toDomain()!!)
        assertEquals(emptyList<Concern>(), visible!!.snapshot.briefing.unacknowledged)
        job.cancel()
        val nextJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.visible(snapshot).collect { visible = it } }
        cache.cards.value = listOf(card().toDomain()!!.copy(revision = 2, sourceIds = emptyList(), title = "Detached"))
        assertTrue(visible!!.hiddenConcerns)
        assertEquals(emptyList<Concern>(), visible!!.snapshot.briefing.newConcerns)
        nextJob.cancel()
    }

    @Test
    fun snapshotArrivingAfterCachedDeletionIsHiddenFromItsFirstEmission() = runTest {
        val snapshot = (repo.load() as ReadResult.Ok).value
        // Removal happened while the response was in flight, before observing the snapshot.
        cache.cards.value = emptyList()
        var visible: VisibleBriefing? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.visible(snapshot).collect { visible = it } }
        assertTrue(visible!!.hiddenConcerns)
        assertTrue(visible!!.snapshot.briefing.newConcerns.isEmpty())
        assertTrue(visible!!.snapshot.briefing.unacknowledged.isEmpty())
        // A matching, genuinely new download can appear; removed rows after this point stay hidden.
        cache.cards.value = listOf(card().toDomain()!!)
        assertEquals(1, visible!!.snapshot.briefing.newConcerns.size)
    }

    @Test
    fun malformedOrOversizedSnapshotsCannotBeReviewed() = runTest {
        api.loaded = briefing().copy(throughTime = "not-a-date")
        assertEquals(ReadResult.Unavailable("malformed_response"), repo.load())
        api.loaded = briefing().copy(newConcerns = List(101) { card() })
        assertEquals(ReadResult.Unavailable("malformed_response"), repo.load())
        api.loaded = briefing().copy(newSourceIds = listOf("../../secret"))
        assertEquals(ReadResult.Unavailable("malformed_response"), repo.load())
        assertTrue(api.requests.isEmpty())
    }
}
