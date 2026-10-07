package com.digifender.adminifender.ui

import com.digifender.adminifender.core.data.network.*
import com.digifender.adminifender.core.data.repository.ReviewRepository
import com.digifender.adminifender.core.data.repository.deletionDigest
import com.digifender.adminifender.core.data.session.PasskeyAuthenticator
import com.digifender.adminifender.core.data.session.PasskeyResult
import com.digifender.adminifender.core.data.session.RecentAuthOutcome
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

private const val UI_SOURCE = "00000000-0000-4000-9000-000000000071"
private const val UI_REVIEW = "00000000-0000-4000-9000-000000000072"
private const val UI_OPERATION = "00000000-0000-4000-9000-000000000073"
private const val UI_NOW = 1_800_000_000_000L

private class UiReviews : ReviewApi {
    var creates = 0
    var confirms = 0
    var result: TransferResult<ConfirmedDeletion> = TransferResult.Ok(ConfirmedDeletion(UI_OPERATION, "succeeded", DeletionReceipt(1, "a".repeat(64), 1)))
    override suspend fun reviewDeletion(sourceIds: List<String>, key: String): TransferResult<DeleteReview> {
        creates++
        val params = DeleteParams(listOf(UI_SOURCE))
        val revisions = DeletePreconditions(mapOf(UI_SOURCE to 1), emptyMap())
        return TransferResult.Ok(DeleteReview(UI_REVIEW, "archive.delete", params, deletionDigest(params, revisions), revisions,
            DeletePreview(listOf(ReviewedSource(UI_SOURCE, "Synthetic", "2026-10-07T12:00:00Z", "normal")), 0, emptyList(), emptyList(), emptyList(), 1),
            Instant.ofEpochMilli(UI_NOW + 600_000).toString(), true))
    }
    override suspend fun confirmDeletion(reviewId: String, digest: String, key: String): TransferResult<ConfirmedDeletion> { confirms++; return result }
    override suspend fun operation(id: String): TransferResult<DeletionOperation> = error("not used")
}

@OptIn(ExperimentalCoroutinesApi::class)
class DeletionViewModelTest {
    private val api = UiReviews()
    private var now = UI_NOW
    private val repo = ReviewRepository(api, { now }, { _, _ -> })
    private val passkeys = PasskeyAuthenticator { PasskeyResult.Cancelled }

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    @Test
    fun cancelledPasskeyDoesNotSubmitTheReviewedDeletion() = runTest {
        val model = DeletionViewModel(listOf(UI_SOURCE), repo) { RecentAuthOutcome.Cancelled }
        model.confirm(passkeys)
        assertEquals(DeletionPhase.REVIEW, model.state.value.phase)
        assertEquals("auth_cancelled", model.state.value.code)
        assertEquals(0, api.confirms)
    }

    @Test
    fun expiryWhilePasskeyPromptIsOpenRequiresFreshReview() = runTest {
        val auth = CompletableDeferred<RecentAuthOutcome>()
        val model = DeletionViewModel(listOf(UI_SOURCE), repo) { auth.await() }
        model.confirm(passkeys)
        assertEquals(DeletionPhase.AUTHENTICATING, model.state.value.phase)
        now += 600_000
        auth.complete(RecentAuthOutcome.Confirmed(now))
        assertEquals(DeletionPhase.NEEDS_REVIEW, model.state.value.phase)
        assertEquals(0, api.confirms)
    }

    @Test
    fun duplicateTapsDoNotRepeatAndServerClockSkewDoesNotInvalidateAFreshPrompt() = runTest {
        val auth = CompletableDeferred<RecentAuthOutcome>()
        var prompts = 0
        val model = DeletionViewModel(listOf(UI_SOURCE), repo) { prompts++; auth.await() }
        model.confirm(passkeys)
        model.confirm(passkeys)
        // The server's UTC clock may be ahead of the phone; freshness starts when this local prompt completes.
        auth.complete(RecentAuthOutcome.Confirmed(now + 30_000))
        assertEquals(1, prompts)
        assertEquals(1, api.confirms)
        assertEquals(DeletionPhase.SUCCEEDED, model.state.value.phase)
    }

    @Test
    fun unansweredConfirmationCannotBeReplacedWithAnotherReview() = runTest {
        api.result = TransferResult.Unavailable("request_in_progress")
        val model = DeletionViewModel(listOf(UI_SOURCE), repo) { RecentAuthOutcome.Confirmed(now) }
        model.confirm(passkeys)
        assertEquals(DeletionPhase.UNKNOWN, model.state.value.phase)
        model.prepare()
        assertEquals(1, api.creates)
        assertEquals(DeletionPhase.UNKNOWN, model.state.value.phase)
    }
}
