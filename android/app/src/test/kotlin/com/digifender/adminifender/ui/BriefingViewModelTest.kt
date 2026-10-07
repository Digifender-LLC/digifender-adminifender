package com.digifender.adminifender.ui

import com.digifender.adminifender.core.data.network.BriefingApi
import com.digifender.adminifender.core.data.network.BriefingDto
import com.digifender.adminifender.core.data.network.BriefingReviewDto
import com.digifender.adminifender.core.data.network.BriefingReviewResult
import com.digifender.adminifender.core.data.network.ConcernDto
import com.digifender.adminifender.core.data.network.ReadResult
import com.digifender.adminifender.core.data.network.toDomain
import com.digifender.adminifender.core.data.repository.BriefingRepository
import com.digifender.adminifender.core.data.repository.CacheSource
import com.digifender.adminifender.core.data.sync.QueuedMutation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val ID = "00000000-0000-4000-8000-000000000001"
private const val CARD_ID = "00000000-0000-4000-8000-000000000002"
private const val WHEN = "2026-10-07T09:00:00Z"
private fun dto() = ConcernDto(CARD_ID, 1, "Synthetic invoice", "open", "important", updatedAt = WHEN)
private fun brief() = BriefingDto(ID, 8, WHEN, newConcerns = listOf(dto()), changedConcerns = emptyList(), overdue = emptyList(), unacknowledged = emptyList(), newSourceIds = emptyList())
private class FakeBriefings : BriefingApi {
    var reads = 0
    var read: ReadResult<BriefingDto> = ReadResult.Ok(brief())
    var result: BriefingReviewResult = BriefingReviewResult.NoAnswer
    var gate: CompletableDeferred<Unit>? = null
    val reviews = mutableListOf<Pair<String, String>>()
    override suspend fun current(): ReadResult<BriefingDto> { reads++; return read }
    override suspend fun review(id: String, idempotencyKey: String): BriefingReviewResult { reviews += id to idempotencyKey; gate?.await(); return result }
}
private class BriefingTestCache : CacheSource {
    val concerns = MutableStateFlow(listOf(dto().toDomain()!!))
    override fun concerns() = concerns
    override fun mutations() = MutableStateFlow(emptyList<QueuedMutation>())
    override fun syncState() = MutableStateFlow<Pair<Long?, Boolean>>(null to false)
}

@OptIn(ExperimentalCoroutinesApi::class)
class BriefingViewModelTest {
    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    @Test
    fun openingNeverReviewsAndAnUnansweredReviewRetriesTheExactDisplayedSnapshot() = runTest {
        val api = FakeBriefings()
        var key = 0
        val model = BriefingViewModel(BriefingRepository(api, BriefingTestCache()) { "key-${++key}" })
        assertEquals(1, api.reads)
        assertTrue(api.reviews.isEmpty())
        model.review()
        assertEquals(BriefingMessage.REVIEW_UNKNOWN, model.state.value.message)
        api.read = ReadResult.Ok(brief().copy(snapshotSeq = 25))
        api.result = BriefingReviewResult.Reviewed(BriefingReviewDto(ID, 8, WHEN))
        model.review()
        assertEquals(BriefingReviewState.REVIEWED, model.state.value.review)
        model.review()
        assertEquals(listOf(ID to "key-1", ID to "key-1"), api.reviews)
    }

    @Test
    fun inFlightReviewBlocksDoubleTapAndRefreshAndFailedRefreshRetainsSnapshot() = runTest {
        val api = FakeBriefings()
        val model = BriefingViewModel(BriefingRepository(api, BriefingTestCache()) { "key" })
        api.gate = CompletableDeferred()
        model.review()
        model.review()
        model.refresh()
        assertEquals(1, api.reads)
        assertEquals(1, api.reviews.size)
        api.gate!!.complete(Unit)
        runCurrent()
        api.read = ReadResult.Unavailable("network")
        model.refresh()
        assertEquals(ID, model.state.value.briefing!!.snapshot.briefing.id)
        assertEquals(BriefingMessage.LOAD_FAILED, model.state.value.message)
    }

    @Test
    fun initiallyMissingOrDifferentRevisionCannotBeReviewedUntilMatchingCacheArrives() = runTest {
        val api = FakeBriefings()
        val cache = BriefingTestCache().apply { concerns.value = emptyList() }
        val model = BriefingViewModel(BriefingRepository(api, cache))
        assertTrue(model.state.value.briefing!!.hiddenConcerns)
        model.review()
        assertTrue(api.reviews.isEmpty())
        cache.concerns.value = listOf(dto().toDomain()!!.copy(revision = 2))
        runCurrent()
        model.review()
        assertTrue(api.reviews.isEmpty())
        cache.concerns.value = listOf(dto().toDomain()!!)
        runCurrent()
        api.result = BriefingReviewResult.Reviewed(BriefingReviewDto(ID, 8, WHEN))
        model.review()
        assertEquals(BriefingReviewState.REVIEWED, model.state.value.review)
        assertEquals(1, api.reviews.size)
    }

    @Test
    fun syncedDeletionHidesContentAndPreventsReviewingAnIncompleteDisplay() = runTest {
        val api = FakeBriefings()
        val cache = BriefingTestCache()
        val model = BriefingViewModel(BriefingRepository(api, cache))
        cache.concerns.value = emptyList()
        runCurrent()
        assertTrue(model.state.value.briefing!!.hiddenConcerns)
        assertTrue(model.state.value.briefing!!.snapshot.briefing.newConcerns.isEmpty())
        model.review()
        assertTrue(api.reviews.isEmpty())
    }
}
