package com.digifender.adminifender.ui

import com.digifender.adminifender.core.data.network.BriefingApi
import com.digifender.adminifender.core.data.network.BriefingDto
import com.digifender.adminifender.core.data.network.ReadResult
import com.digifender.adminifender.core.data.network.ReviewResult
import com.digifender.adminifender.core.data.network.SourceSummary
import com.digifender.adminifender.core.data.network.wireJson
import com.digifender.adminifender.core.data.repository.Briefing
import com.digifender.adminifender.core.data.repository.BriefingCache
import com.digifender.adminifender.core.data.repository.BriefingItem
import com.digifender.adminifender.core.data.repository.BriefingRecord
import com.digifender.adminifender.core.data.repository.BriefingRepository
import com.digifender.adminifender.core.data.repository.CachedReason
import com.digifender.adminifender.core.data.repository.ConcernRepository
import com.digifender.adminifender.core.data.repository.ReviewOutcome
import com.digifender.adminifender.core.data.repository.ReviewPhase
import com.digifender.adminifender.core.data.repository.StoredReview
import com.digifender.adminifender.core.data.repository.overlay
import com.digifender.adminifender.core.data.sync.MutationState
import com.digifender.adminifender.core.data.sync.QueuedMutation
import com.digifender.adminifender.core.model.Concern
import com.digifender.adminifender.core.model.ConcernAction
import com.digifender.adminifender.core.model.Due
import com.digifender.adminifender.core.model.Importance
import com.digifender.adminifender.core.model.PendingMutation
import com.digifender.adminifender.core.model.Status
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val B1 = "4f8c0d2e-6a71-4c93-8e3f-5a6b7c8d9e0f"
private const val B2 = "5a9d1e3f-7b82-4da4-9f40-6b7c8d9e0f1a"
private const val C1 = "0b4e6f8a-2c3d-4e5f-8a9b-1c2d3e4f5a6b"
private const val C2 = "1c5f7a9b-3d4e-4f60-9b0c-2d3e4f5a6b7c"
private const val C3 = "2d6a8b0c-4e5f-4a71-8c1d-3e4f5a6b7c8d"
private const val SRC = "6f1c2a10-8b1e-4c6d-9a51-3e0b7d2f4a11"

private fun concern(id: String, revision: Int = 2, status: Status = Status.OPEN, ack: Long? = null, dueAt: Long? = null) = Concern(
    id, revision, "Item $id".take(12), status, Importance.ROUTINE, null, null, ack, null, null, null,
    dueAt?.let { Due(it, "UTC", null, 1) }, emptyList(), NOW - 1,
)

private fun source(id: String, state: String = "ready", quarantined: Boolean = false) = SourceSummary(id, 1, state, quarantined, "hybrid", NOW - 10, "Invoice", listOf("a@b.example"))

private fun projection(id: String, revision: Int) =
    """{"id":"$id","revision":$revision,"title":"T","status":"open","importance":"routine","criticalBasis":null,"serviceId":null,"readAt":null,"acknowledgedAt":null,"resolvedAt":null,"snoozeUntil":null,"waiting":null,"due":null,"sourceIds":[],"updatedAt":"2026-10-03T12:00:00.000Z"}"""

private fun dto(id: String = B1, seq: Long = 57, news: List<Pair<String, Int>> = listOf(C1 to 2), overdue: List<Pair<String, Int>> = emptyList(), unack: List<Pair<String, Int>> = listOf(C1 to 2, C2 to 2)): BriefingDto {
    fun list(items: List<Pair<String, Int>>) = items.joinToString(",", "[", "]") { projection(it.first, it.second) }
    return wireJson.decodeFromString(
        BriefingDto.serializer(),
        """{"id":"$id","snapshotSeq":$seq,"throughTime":"2026-10-08T07:00:00.000Z","lastReviewedAt":null,"newConcerns":${list(news)},"changedConcerns":[],"overdue":${list(overdue)},"unacknowledged":${list(unack)},"newSourceIds":["$SRC"],"hasMore":false,"summary":null}""",
    )
}

private class BriefingFakeCache : BriefingCache {
    val state = MutableStateFlow<BriefingRecord?>(null)
    override fun current() = state
    override suspend fun replace(briefing: Briefing) {
        state.value = BriefingRecord(briefing, state.value?.takeIf { it.briefing.id == briefing.id }?.review ?: StoredReview.NONE)
    }
    override suspend fun setReview(briefingId: String, review: StoredReview): Boolean {
        val r = state.value?.takeIf { it.briefing.id == briefingId } ?: return false
        state.value = r.copy(review = review)
        return true
    }
}

private class BriefingFakeApi : BriefingApi {
    var read: suspend () -> ReadResult<BriefingDto> = { ReadResult.Ok(dto()) }
    var review: suspend (String, String) -> ReviewResult = { id, _ -> ReviewResult.Reviewed(id, 57, NOW) }
    val reviewCalls = mutableListOf<Pair<String, String>>()
    var reads = 0
    override suspend fun currentBriefing(): ReadResult<BriefingDto> {
        reads++
        return read()
    }
    override suspend fun reviewBriefing(id: String, idempotencyKey: String): ReviewResult {
        reviewCalls += id to idempotencyKey
        return review(id, idempotencyKey)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BriefingViewModelTest {
    private val fake = Fake().apply { concern.value = listOf(concern(C1), concern(C2)) }
    private val concerns = ConcernRepository(fake.cache, fake, NoSync, { NOW }, { "k" })
    private val sources = MutableStateFlow(listOf(source(SRC)))
    private fun cachedSources(ids: List<String>) = sources.map { list -> list.filter { it.id in ids } }
    private val cache = BriefingFakeCache()
    private val api = BriefingFakeApi()
    private var keys = 0
    private val briefings = BriefingRepository(api, cache, { NOW }, { "key-${++keys}" })

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    private fun server(state: BriefingUiState) = state.content as BriefingContent.FromServer

    @Test
    fun openingReadsTheBriefingIntoTheCacheAndNeverReviewsIt() = runTest {
        val model = BriefingViewModel(briefings, concerns, ::cachedSources) { NOW }
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        val s = model.state.value
        assertEquals(false, s.loading)
        assertNull(s.problem)
        val content = server(s)
        assertEquals(B1, content.id)
        assertEquals(listOf(C1), content.sections.newItems.map { it.concern.id })
        assertEquals(listOf(C1, C2), content.sections.unacknowledged.map { it.concern.id })
        assertEquals(listOf(SRC), content.newSources.map { it.id })
        assertNull(content.reviewBlock)
        assertEquals(StoredReview.NONE, content.review)
        // The screen shows what the cache holds.
        assertEquals(B1, cache.state.value?.briefing?.id)
        assertTrue(api.reviewCalls.isEmpty())
        job.cancel()
    }

    @Test
    fun withoutAnySnapshotOnlyThePhoneComputedSectionsAreShownAndNothingCanBeReviewed() = runTest {
        api.read = { ReadResult.Unavailable("network") }
        fake.concern.value = listOf(concern(C1, dueAt = NOW - 1), concern(C2, ack = NOW - 5))
        val model = BriefingViewModel(briefings, concerns, ::cachedSources) { NOW }
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        val s = model.state.value
        assertEquals(CachedReason.OFFLINE, s.problem)
        val phone = s.content as BriefingContent.FromPhone
        assertEquals(listOf(C1), phone.overdue.map { it.concern.id })
        assertEquals(listOf(C1), phone.unacknowledged.map { it.concern.id })
        model.markReviewed()
        assertTrue(api.reviewCalls.isEmpty())
        job.cancel()
    }

    @Test
    fun aFailedRefreshKeepsTheStoredSnapshotAndNamesTheProblem() = runTest {
        val model = BriefingViewModel(briefings, concerns, ::cachedSources) { NOW }
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        api.read = { ReadResult.SessionExpired }
        model.refresh()
        assertEquals(CachedReason.SIGN_IN, model.state.value.problem)
        assertEquals(B1, server(model.state.value).id)
        api.read = { ReadResult.Ok(dto()) }
        model.refresh()
        assertNull(model.state.value.problem)
        job.cancel()
    }

    @Test
    fun aRefreshRunningBlocksAnotherRefreshAndAReview() = runTest {
        val model = BriefingViewModel(briefings, concerns, ::cachedSources) { NOW }
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        val gate = CompletableDeferred<ReadResult<BriefingDto>>()
        api.read = { gate.await() }
        val before = api.reads
        model.refresh()
        model.refresh()
        model.markReviewed()
        assertEquals(before + 1, api.reads)
        assertTrue(model.state.value.loading)
        assertTrue(api.reviewCalls.isEmpty())
        gate.complete(ReadResult.Ok(dto()))
        assertEquals(false, model.state.value.loading)
        job.cancel()
    }

    @Test
    fun enteringTheScreenAgainDismissesTheOldMessageAndReadsAgainWithoutReviewing() = runTest {
        val model = BriefingViewModel(briefings, concerns, ::cachedSources) { NOW }
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        model.markReviewed()
        assertEquals(ReviewMessage.REVIEWED, model.state.value.message)
        val reads = api.reads
        val reviews = api.reviewCalls.size
        // A newer snapshot is waiting on the server.
        api.read = { ReadResult.Ok(dto(id = B2, seq = 60, news = emptyList(), unack = listOf(C1 to 2))) }
        model.onOpened()
        assertNull(model.state.value.message)
        assertEquals(reads + 1, api.reads)
        assertEquals(B2, server(model.state.value).id)
        assertEquals(B2, cache.state.value?.briefing?.id)
        // Reading is not reviewing: the new snapshot is not marked and no review request was sent.
        assertEquals(StoredReview.NONE, server(model.state.value).review)
        assertEquals(reviews, api.reviewCalls.size)
        job.cancel()
    }

    @Test
    fun enteringAgainKeepsTheCachedViewAndAnUnconfirmedReviewWhenTheServerCannotBeReached() = runTest {
        val model = BriefingViewModel(briefings, concerns, ::cachedSources) { NOW }
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        api.review = { _, _ -> ReviewResult.NoAnswer }
        model.markReviewed()
        assertEquals(ReviewMessage.NOT_CONFIRMED, model.state.value.message)
        api.read = { ReadResult.Unavailable("network") }
        model.onOpened()
        val state = model.state.value
        assertNull(state.message)
        assertEquals(CachedReason.OFFLINE, state.problem)
        val content = server(state)
        assertEquals(B1, content.id)
        assertEquals(ReviewPhase.UNCONFIRMED, content.review.phase)
        assertTrue(content.canReview)
        job.cancel()
    }

    @Test
    fun enteringWhileAReadOrReviewIsRunningStartsNothingExtra() = runTest {
        val model = BriefingViewModel(briefings, concerns, ::cachedSources) { NOW }
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        val gate = CompletableDeferred<ReadResult<BriefingDto>>()
        api.read = { gate.await() }
        val before = api.reads
        model.onOpened()
        model.onOpened()
        assertEquals(before + 1, api.reads)
        gate.complete(ReadResult.Ok(dto()))
        job.cancel()
    }

    @Test
    fun markReviewedCoversTheSnapshotOnScreenOnceAndAcknowledgesNothing() = runTest {
        val model = BriefingViewModel(briefings, concerns, ::cachedSources) { NOW }
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        model.markReviewed()
        assertEquals(ReviewMessage.REVIEWED, model.state.value.message)
        assertEquals(ReviewPhase.DONE, server(model.state.value).review.phase)
        model.markReviewed()
        assertEquals(1, api.reviewCalls.size)
        // No concern edit was queued by reviewing.
        assertTrue(fake.queue.value.isEmpty())
        assertEquals(Status.OPEN, fake.concern.value.first().status)
        model.messageShown()
        assertNull(model.state.value.message)
        job.cancel()
    }

    @Test
    fun anUnansweredReviewStaysVisibleAsUnconfirmedAndIsRetriedAsTheSameRequest() = runTest {
        val model = BriefingViewModel(briefings, concerns, ::cachedSources) { NOW }
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        api.review = { _, _ -> ReviewResult.NoAnswer }
        model.markReviewed()
        assertEquals(ReviewMessage.NOT_CONFIRMED, model.state.value.message)
        assertEquals(ReviewPhase.UNCONFIRMED, server(model.state.value).review.phase)
        api.review = { id, _ -> ReviewResult.Reviewed(id, 57, NOW) }
        model.markReviewed()
        assertEquals(ReviewPhase.DONE, server(model.state.value).review.phase)
        assertEquals(listOf("key-1", "key-1"), api.reviewCalls.map { it.second })
        job.cancel()
    }

    @Test
    fun aSnapshotThatMovedOnIsNotReviewedFromAStaleScreen() = runTest {
        val model = BriefingViewModel(briefings, concerns, ::cachedSources) { NOW }
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        // The cache moves to a newer snapshot while the owner is still looking at the old one.
        cache.state.value = BriefingRecord(cache.state.value!!.briefing.copy(id = "5a9d1e3f-7b82-4da4-9f40-6b7c8d9e0f1a", snapshotSeq = 60), StoredReview.NONE)
        assertEquals(ReviewOutcome.Stale, briefings.markReviewed(B1))
        assertTrue(api.reviewCalls.isEmpty())
        job.cancel()
    }

    @Test
    fun cardsFollowTheSyncedCacheSoADeletedItemLeavesTheBriefing() = runTest {
        val model = BriefingViewModel(briefings, concerns, ::cachedSources) { NOW }
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        assertEquals(0, server(model.state.value).sections.notOnPhone)
        fake.concern.value = listOf(concern(C1))
        val s = server(model.state.value).sections
        assertEquals(listOf(C1), s.unacknowledged.map { it.concern.id })
        assertEquals(1, s.notOnPhone)
        job.cancel()
    }

    @Test
    fun cardsShowQueuedEditsAndFlagAnOlderSyncedRevision() {
        val record = BriefingRecord(
            Briefing(B1, 57, NOW, null, NOW, emptyList(), emptyList(), emptyList(), listOf(BriefingItem(C1, 5), BriefingItem(C2, 2)), emptyList()),
            StoredReview.NONE,
        )
        val queued = QueuedMutation(PendingMutation("k", C2, 2, ConcernAction.Acknowledge, NOW - 1), MutationState.QUEUED, NOW - 1, null, null)
        val views = overlay(listOf(concern(C1, revision = 3), concern(C2)), listOf(queued), NOW)
        val cards = (buildBriefingContent(record, views, emptyList(), NOW) as BriefingContent.FromServer).sections.unacknowledged
        assertEquals(listOf(C1, C2), cards.map { it.concern.id })
        assertEquals(listOf(true, false), cards.map { it.behind })
        assertEquals(listOf(false, true), cards.map { it.pending })
        // The edit shows on the card, and the snapshot itself is untouched.
        assertTrue(cards[1].concern.acknowledgedAtMs != null)
    }

    private fun TestScope.watch(model: BriefingViewModel) = launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }

    @Test
    fun reviewIsNotOfferedWhileAListedConcernIsMissingFromThePhone() = runTest {
        fake.concern.value = listOf(concern(C1))
        val model = BriefingViewModel(briefings, concerns, ::cachedSources) { NOW }
        val job = watch(model)
        val blocked = server(model.state.value)
        assertEquals(ReviewBlock.NOT_ON_PHONE, blocked.reviewBlock)
        assertEquals(false, blocked.canReview)
        assertEquals(1, blocked.sections.notOnPhone)
        // A tap that gets through anyway sends nothing and advances nothing.
        model.markReviewed()
        assertTrue(api.reviewCalls.isEmpty())
        assertEquals(ReviewPhase.NONE, server(model.state.value).review.phase)
        // The next sync brings it in; the same snapshot can then be reviewed.
        fake.concern.value = listOf(concern(C1), concern(C2))
        assertEquals(true, server(model.state.value).canReview)
        model.markReviewed()
        assertEquals(ReviewMessage.REVIEWED, model.state.value.message)
        job.cancel()
    }

    @Test
    fun reviewIsNotOfferedWhileAListedConcernIsSyncedAtAnOlderRevision() = runTest {
        fake.concern.value = listOf(concern(C1), concern(C2, revision = 1))
        val model = BriefingViewModel(briefings, concerns, ::cachedSources) { NOW }
        val job = watch(model)
        val s = server(model.state.value)
        assertEquals(ReviewBlock.BEHIND, s.reviewBlock)
        assertEquals(0, s.sections.notOnPhone)
        assertEquals(true, s.sections.unacknowledged.single { it.concern.id == C2 }.behind)
        model.markReviewed()
        assertTrue(api.reviewCalls.isEmpty())
        // A newer synced revision than the snapshot showed is fine: the owner sees at least what the snapshot covered.
        fake.concern.value = listOf(concern(C1, revision = 9), concern(C2))
        assertNull(server(model.state.value).reviewBlock)
        job.cancel()
    }

    @Test
    fun aListedEmailThatIsNotCachedReadyAndNormalBlocksReviewAndIsNeverListed() = runTest {
        val model = BriefingViewModel(briefings, concerns, ::cachedSources) { NOW }
        val job = watch(model)
        assertEquals(listOf(SRC), server(model.state.value).newSources.map { it.id })
        // Not synced yet.
        sources.value = emptyList()
        assertEquals(ReviewBlock.NOT_ON_PHONE, server(model.state.value).reviewBlock)
        assertEquals(1, server(model.state.value).sections.notOnPhone)
        // Being deleted, deleted, or now quarantined: kept out of the list, and still blocks.
        for (blocked in listOf(source(SRC, state = "deleting"), source(SRC, state = "deleted"), source(SRC, quarantined = true))) {
            sources.value = listOf(blocked)
            val s = server(model.state.value)
            assertEquals(emptyList<SourceSummary>(), s.newSources)
            assertEquals(ReviewBlock.NOT_ON_PHONE, s.reviewBlock)
            assertEquals(false, s.canReview)
        }
        model.markReviewed()
        assertTrue(api.reviewCalls.isEmpty())
        sources.value = listOf(source(SRC))
        assertNull(server(model.state.value).reviewBlock)
        job.cancel()
    }

    @Test
    fun aDeletionBlocksTheOldSnapshotUntilARefreshReplacesIt() = runTest {
        val model = BriefingViewModel(briefings, concerns, ::cachedSources) { NOW }
        val job = watch(model)
        sources.value = emptyList()
        fake.concern.value = listOf(concern(C1))
        assertEquals(2, server(model.state.value).sections.notOnPhone)
        assertEquals(false, server(model.state.value).canReview)
        // The server's next snapshot no longer lists the deleted items.
        api.read = { ReadResult.Ok(dto(id = "5a9d1e3f-7b82-4da4-9f40-6b7c8d9e0f1a", seq = 60, news = emptyList(), unack = listOf(C1 to 2)).copy(newSourceIds = emptyList())) }
        model.refresh()
        val s = server(model.state.value)
        assertEquals(0, s.sections.notOnPhone)
        assertEquals(true, s.canReview)
        model.markReviewed()
        assertEquals(listOf("5a9d1e3f-7b82-4da4-9f40-6b7c8d9e0f1a"), api.reviewCalls.map { it.first })
        job.cancel()
    }

    @Test
    fun anAnswerLessReviewCanBeRepeatedAsTheSameRequestEvenIfTheCacheChangedSince() = runTest {
        val model = BriefingViewModel(briefings, concerns, ::cachedSources) { NOW }
        val job = watch(model)
        api.review = { _, _ -> ReviewResult.NoAnswer }
        model.markReviewed()
        assertEquals(ReviewPhase.UNCONFIRMED, server(model.state.value).review.phase)
        // Afterwards an item is deleted. A first review would now be blocked, but the repeat only reconciles the
        // request already sent for the snapshot the owner saw, with the same key.
        fake.concern.value = listOf(concern(C1))
        val blocked = server(model.state.value)
        assertEquals(ReviewBlock.NOT_ON_PHONE, blocked.reviewBlock)
        assertEquals(true, blocked.canReview)
        api.review = { _, _ -> ReviewResult.AlreadyReviewed }
        model.markReviewed()
        assertEquals(ReviewMessage.ALREADY, model.state.value.message)
        assertEquals(listOf("key-1", "key-1"), api.reviewCalls.map { it.second })
        assertEquals(false, server(model.state.value).canReview)
        job.cancel()
    }
}
