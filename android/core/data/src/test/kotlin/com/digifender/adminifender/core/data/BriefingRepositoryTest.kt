package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.BriefingApi
import com.digifender.adminifender.core.data.network.BriefingDto
import com.digifender.adminifender.core.data.network.ReadResult
import com.digifender.adminifender.core.data.network.ReviewResult
import com.digifender.adminifender.core.data.network.wireJson
import com.digifender.adminifender.core.data.repository.Briefing
import com.digifender.adminifender.core.data.repository.BriefingCache
import com.digifender.adminifender.core.data.repository.BriefingItem
import com.digifender.adminifender.core.data.repository.BriefingItemsCodec
import com.digifender.adminifender.core.data.repository.BriefingRecord
import com.digifender.adminifender.core.data.repository.BriefingRepository
import com.digifender.adminifender.core.data.repository.CachedReason
import com.digifender.adminifender.core.data.repository.RefreshOutcome
import com.digifender.adminifender.core.data.repository.ReviewOutcome
import com.digifender.adminifender.core.data.repository.ReviewPhase
import com.digifender.adminifender.core.data.repository.StoredReview
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_800_000_000_000L
private const val BRIEFING_ID = "4f8c0d2e-6a71-4c93-8e3f-5a6b7c8d9e0f"
private const val OTHER_ID = "5a9d1e3f-7b82-4da4-9f40-6b7c8d9e0f1a"
private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

private fun fixture(name: String = "valid/four-sections.json"): BriefingDto = wireJson.decodeFromString(BriefingDto.serializer(), contractFile("fixtures/v1/briefing/$name"))

/** Cache with a hook that runs just before a review update, to replace the row at the worst moment. */
private class InMemoryBriefingCache : BriefingCache {
    val state = MutableStateFlow<BriefingRecord?>(null)
    var beforeSetReview: (() -> Unit)? = null
    var replaces = 0

    override fun current() = state

    override suspend fun replace(briefing: Briefing) {
        val keep = state.value?.takeIf { it.briefing.id == briefing.id }?.review ?: StoredReview.NONE
        state.value = BriefingRecord(briefing, keep)
        replaces++
    }

    override suspend fun setReview(briefingId: String, review: StoredReview): Boolean {
        beforeSetReview?.invoke()
        val record = state.value ?: return false
        if (record.briefing.id != briefingId) return false
        state.value = record.copy(review = review)
        return true
    }
}

private class FakeBriefingApi : BriefingApi {
    val reads = ArrayDeque<suspend () -> ReadResult<BriefingDto>>()
    val reviews = ArrayDeque<suspend (String, String) -> ReviewResult>()
    val reviewCalls = mutableListOf<Pair<String, String>>()
    var reviewSawCache: ((String) -> Unit)? = null

    override suspend fun currentBriefing() = reads.removeFirst()()

    override suspend fun reviewBriefing(id: String, idempotencyKey: String): ReviewResult {
        reviewCalls += id to idempotencyKey
        reviewSawCache?.invoke(idempotencyKey)
        return reviews.removeFirst()(id, idempotencyKey)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BriefingRepositoryTest {
    private val api = FakeBriefingApi()
    private val cache = InMemoryBriefingCache()
    private var keys = 0
    private val repo = BriefingRepository(api, cache, { NOW }, { "key-${++keys}" })

    private fun serve(dto: BriefingDto) = api.reads.addLast { ReadResult.Ok(dto) }

    private suspend fun stored(dto: BriefingDto = fixture()): Briefing {
        serve(dto)
        assertEquals(RefreshOutcome.Stored, repo.refresh())
        return cache.state.value!!.briefing
    }

    @Test
    fun theSnapshotIsStoredByReferenceAndNothingIsReviewedByFetchingIt() = runTest {
        val b = stored()
        assertEquals(BRIEFING_ID, b.id)
        assertEquals(57, b.snapshotSeq)
        assertEquals(ms("2026-10-08T07:00:00Z"), b.throughTimeMs)
        assertEquals(ms("2026-10-07T07:00:00Z"), b.lastReviewedAtMs)
        assertEquals(NOW, b.fetchedAtMs)
        assertEquals(listOf(BriefingItem("0b4e6f8a-2c3d-4e5f-8a9b-1c2d3e4f5a6b", 2)), b.newItems)
        assertEquals(1, b.changedItems.size)
        assertEquals(1, b.overdueItems.size)
        assertEquals(2, b.unacknowledgedItems.size)
        assertEquals(listOf("6f1c2a10-8b1e-4c6d-9a51-3e0b7d2f4a11"), b.newSourceIds)
        assertEquals(StoredReview.NONE, cache.state.value!!.review)
        assertTrue(api.reviewCalls.isEmpty())
    }

    @Test
    fun aCappedSnapshotKeepsItsMoreWaitingSignalThroughTheStoredRow() = runTest {
        assertFalse(stored().hasMore)
        val capped = stored(fixture().copy(hasMore = true))
        assertTrue(capped.hasMore)
        val base = capped.copy(newItems = emptyList(), changedItems = emptyList(), overdueItems = emptyList(), unacknowledgedItems = emptyList(), newSourceIds = emptyList(), hasMore = false)
        assertEquals(capped, BriefingItemsCodec.decode(BriefingItemsCodec.encode(capped), base))
    }

    @Test
    fun anEmptyFirstBriefingIsStillABriefing() = runTest {
        val b = stored(fixture("valid/first-briefing-empty.json"))
        assertNull(b.lastReviewedAtMs)
        assertEquals(emptyList<BriefingItem>(), b.newItems + b.changedItems + b.overdueItems + b.unacknowledgedItems)
    }

    @Test
    fun aSnapshotThePhoneCannotParseIsNotStored() = runTest {
        serve(fixture())
        repo.refresh()
        val before = cache.state.value
        serve(fixture("invalid/unknown-status.json"))
        assertEquals(RefreshOutcome.Failed(CachedReason.UNAVAILABLE), repo.refresh())
        api.reads.addLast { ReadResult.Ok(fixture().copy(id = "../sync/changes")) }
        assertEquals(RefreshOutcome.Failed(CachedReason.UNAVAILABLE), repo.refresh())
        api.reads.addLast { ReadResult.Ok(fixture().copy(throughTime = "yesterday")) }
        assertEquals(RefreshOutcome.Failed(CachedReason.UNAVAILABLE), repo.refresh())
        assertEquals(before, cache.state.value)
    }

    @Test
    fun aFailedRefreshKeepsTheStoredBriefingAndSaysWhy() = runTest {
        val b = stored()
        api.reads.addLast { ReadResult.Unavailable("network") }
        api.reads.addLast { ReadResult.SessionExpired }
        api.reads.addLast { ReadResult.Unavailable("unavailable") }
        api.reads.addLast { ReadResult.CursorExpired }
        assertEquals(RefreshOutcome.Failed(CachedReason.OFFLINE), repo.refresh())
        assertEquals(RefreshOutcome.Failed(CachedReason.SIGN_IN), repo.refresh())
        assertEquals(RefreshOutcome.Failed(CachedReason.UNAVAILABLE), repo.refresh())
        assertEquals(RefreshOutcome.Failed(CachedReason.UNAVAILABLE), repo.refresh())
        assertEquals(b, cache.state.value!!.briefing)
        assertEquals(1, cache.replaces)
    }

    @Test
    fun theIntentAndKeyAreStoredBeforeTheRequestAndTheAnswerSettlesIt() = runTest {
        stored()
        var seen: StoredReview? = null
        api.reviewSawCache = { seen = cache.state.value!!.review }
        api.reviews.addLast { id, _ -> ReviewResult.Reviewed(id, 57, ms("2026-10-08T08:00:00Z")) }
        assertEquals(ReviewOutcome.Reviewed(ms("2026-10-08T08:00:00Z")), repo.markReviewed(BRIEFING_ID))
        assertEquals(StoredReview(ReviewPhase.UNCONFIRMED, "key-1"), seen)
        assertEquals(StoredReview(ReviewPhase.DONE, null, ms("2026-10-08T08:00:00Z")), cache.state.value!!.review)
        // Reviewed once: no second request, and the answer is plain.
        assertEquals(ReviewOutcome.AlreadyReviewed, repo.markReviewed(BRIEFING_ID))
        assertEquals(listOf(BRIEFING_ID to "key-1"), api.reviewCalls)
    }

    @Test
    fun anUnansweredSendIsRepeatedAsTheSameRequestEvenAfterARestart() = runTest {
        stored()
        api.reviews.addLast { _, _ -> ReviewResult.NoAnswer }
        assertEquals(ReviewOutcome.NotConfirmed, repo.markReviewed(BRIEFING_ID))
        assertEquals(ReviewPhase.UNCONFIRMED, cache.state.value!!.review.phase)
        // A new repository over the same cache is a restarted process.
        val restarted = BriefingRepository(api, cache, { NOW }, { "key-after-restart" })
        api.reviews.addLast { _, _ -> ReviewResult.NoAnswer }
        assertEquals(ReviewOutcome.NotConfirmed, restarted.markReviewed(BRIEFING_ID))
        api.reviews.addLast { _, _ -> ReviewResult.AlreadyReviewed }
        assertEquals(ReviewOutcome.AlreadyReviewed, restarted.markReviewed(BRIEFING_ID))
        assertEquals(listOf("key-1", "key-1", "key-1"), api.reviewCalls.map { it.second })
        assertEquals(ReviewPhase.DONE, cache.state.value!!.review.phase)
    }

    @Test
    fun refreshingTheSameUnreviewedSnapshotKeepsThePendingIntentButANewSnapshotDropsIt() = runTest {
        stored()
        api.reviews.addLast { _, _ -> ReviewResult.NoAnswer }
        repo.markReviewed(BRIEFING_ID)
        stored()
        assertEquals(StoredReview(ReviewPhase.UNCONFIRMED, "key-1"), cache.state.value!!.review)
        // A later snapshot is what the owner now sees; reviewing it covers the earlier one too.
        stored(fixture().copy(id = OTHER_ID, snapshotSeq = 60))
        assertEquals(StoredReview.NONE, cache.state.value!!.review)
    }

    @Test
    fun refusalsLeaveNoIntentBehind() = runTest {
        stored()
        for ((result, expected) in listOf(
            ReviewResult.NotFound to ReviewOutcome.Gone,
            ReviewResult.Rejected("idempotency_conflict") to ReviewOutcome.Refused("idempotency_conflict"),
            ReviewResult.SessionExpired to ReviewOutcome.SignIn,
        )) {
            api.reviews.addLast { _, _ -> result }
            assertEquals(expected, repo.markReviewed(BRIEFING_ID))
            assertEquals(StoredReview.NONE, cache.state.value!!.review)
        }
        // Each refusal started over with its own key because none was left pending.
        assertEquals(listOf("key-1", "key-2", "key-3"), api.reviewCalls.map { it.second })
    }

    @Test
    fun anAnswerAboutAnotherSnapshotConfirmsNothing() = runTest {
        stored()
        api.reviews.addLast { _, _ -> ReviewResult.Reviewed(OTHER_ID, 57, NOW) }
        assertEquals(ReviewOutcome.Refused("mismatched_response"), repo.markReviewed(BRIEFING_ID))
        assertEquals(StoredReview.NONE, cache.state.value!!.review)
        api.reviews.addLast { id, _ -> ReviewResult.Reviewed(id, 99, NOW) }
        assertEquals(ReviewOutcome.Refused("mismatched_response"), repo.markReviewed(BRIEFING_ID))
        assertEquals(StoredReview.NONE, cache.state.value!!.review)
    }

    @Test
    fun onlyTheBriefingOnScreenCanBeReviewed() = runTest {
        stored()
        assertEquals(ReviewOutcome.Stale, repo.markReviewed(OTHER_ID))
        stored(fixture().copy(id = OTHER_ID, snapshotSeq = 60))
        // The owner was looking at the older snapshot: it must not be reviewed now.
        assertEquals(ReviewOutcome.Stale, repo.markReviewed(BRIEFING_ID))
        assertTrue(api.reviewCalls.isEmpty())
    }

    @Test
    fun nothingIsSentWhenTheIntentCouldNotBeStored() = runTest {
        stored()
        // Sign-out or a refresh replaces the row between reading it and recording the intent.
        cache.beforeSetReview = { cache.state.value = null }
        assertEquals(ReviewOutcome.Stale, repo.markReviewed(BRIEFING_ID))
        assertTrue(api.reviewCalls.isEmpty())
    }

    @Test
    fun twoCallersForOneBriefingShareOneKeyAndOneRequestAtATime() = runTest {
        stored()
        val gate = CompletableDeferred<ReviewResult>()
        api.reviews.addLast { _, _ -> gate.await() }
        val first = async { repo.markReviewed(BRIEFING_ID) }
        runCurrent()
        val second = async { repo.markReviewed(BRIEFING_ID) }
        runCurrent()
        assertEquals(1, api.reviewCalls.size)
        api.reviews.addLast { _, _ -> ReviewResult.NoAnswer }
        gate.complete(ReviewResult.NoAnswer)
        assertEquals(ReviewOutcome.NotConfirmed, first.await())
        assertEquals(ReviewOutcome.NotConfirmed, second.await())
        assertEquals(listOf("key-1", "key-1"), api.reviewCalls.map { it.second })
        assertEquals(1, keys)
    }

    @Test
    fun aSecondCallerAfterASuccessfulReviewSendsNothing() = runTest {
        stored()
        val gate = CompletableDeferred<ReviewResult>()
        api.reviews.addLast { _, _ -> gate.await() }
        val first = async { repo.markReviewed(BRIEFING_ID) }
        runCurrent()
        val second = async { repo.markReviewed(BRIEFING_ID) }
        runCurrent()
        gate.complete(ReviewResult.Reviewed(BRIEFING_ID, 57, NOW))
        assertEquals(ReviewOutcome.Reviewed(NOW), first.await())
        assertEquals(ReviewOutcome.AlreadyReviewed, second.await())
        assertEquals(1, api.reviewCalls.size)
    }

    @Test
    fun aReadInFlightWhenTheOwnerSignsOutNeverReachesTheCache() = runTest {
        val gate = CompletableDeferred<ReadResult<BriefingDto>>()
        api.reads.addLast { gate.await() }
        val refresh = async { repo.refresh() }
        runCurrent()
        // Sign-out: invalidate first, then clear.
        repo.invalidate()
        cache.state.value = null
        gate.complete(ReadResult.Ok(fixture()))
        assertEquals(RefreshOutcome.Failed(CachedReason.SIGN_IN), refresh.await())
        assertNull(cache.state.value)
        assertEquals(0, cache.replaces)
        // The next session reads normally.
        serve(fixture())
        assertEquals(RefreshOutcome.Stored, repo.refresh())
        assertNotNull(cache.state.value)
    }

    @Test
    fun anOlderReadFinishingLateDoesNotReplaceANewerOne() = runTest {
        val slow = CompletableDeferred<ReadResult<BriefingDto>>()
        val fast = CompletableDeferred<ReadResult<BriefingDto>>()
        api.reads.addLast { slow.await() }
        api.reads.addLast { fast.await() }
        val older = async { repo.refresh() }
        runCurrent()
        val newer = async { repo.refresh() }
        runCurrent()
        fast.complete(ReadResult.Ok(fixture().copy(id = OTHER_ID, snapshotSeq = 70)))
        assertEquals(RefreshOutcome.Stored, newer.await())
        // Same sequence, so only the order the reads were started in tells which is newer.
        slow.complete(ReadResult.Ok(fixture().copy(snapshotSeq = 70)))
        assertEquals(RefreshOutcome.Stored, older.await())
        assertEquals(OTHER_ID, cache.state.value!!.briefing.id)
        assertEquals(1, cache.replaces)
    }

    @Test
    fun aSnapshotBehindTheStoredOneIsNeverWrittenButAnEqualOneRefreshesIt() = runTest {
        stored(fixture().copy(snapshotSeq = 80))
        serve(fixture().copy(snapshotSeq = 79))
        assertEquals(RefreshOutcome.Stored, repo.refresh())
        assertEquals(80, cache.state.value!!.briefing.snapshotSeq)
        assertEquals(1, cache.replaces)
        serve(fixture().copy(snapshotSeq = 80))
        repo.refresh()
        assertEquals(2, cache.replaces)
    }

    @Test
    fun sectionsRoundTripThroughTheirStoredForm() {
        val b = Briefing(
            BRIEFING_ID, 5, 10, null, 20,
            listOf(BriefingItem("a", 1)), listOf(BriefingItem("b", 2)), listOf(BriefingItem("c", 3), BriefingItem("d", 4)), emptyList(), listOf("s1"),
        )
        val shell = b.copy(newItems = emptyList(), changedItems = emptyList(), overdueItems = emptyList(), unacknowledgedItems = emptyList(), newSourceIds = emptyList())
        assertEquals(b, BriefingItemsCodec.decode(BriefingItemsCodec.encode(b), shell))
    }
}
