package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.Cleanup
import com.digifender.adminifender.core.data.network.CleanupState
import com.digifender.adminifender.core.data.network.ConfirmResult
import com.digifender.adminifender.core.data.network.Operation
import com.digifender.adminifender.core.data.network.OperationState
import com.digifender.adminifender.core.data.network.PrepareResult
import com.digifender.adminifender.core.data.network.ReviewEffect
import com.digifender.adminifender.core.data.network.ReviewRead
import com.digifender.adminifender.core.data.network.ReviewState
import com.digifender.adminifender.core.data.review.ConfirmOutcome
import com.digifender.adminifender.core.data.review.DeletionPhase
import com.digifender.adminifender.core.data.review.DeletionRepository
import com.digifender.adminifender.core.data.review.PrepareOutcome
import com.digifender.adminifender.core.data.review.ReadbackOutcome
import com.digifender.adminifender.core.data.review.RecentAuthenticator
import com.digifender.adminifender.core.data.review.UnsettledReason
import com.digifender.adminifender.core.data.session.PasskeyAuthenticator
import com.digifender.adminifender.core.data.session.PasskeyResult
import com.digifender.adminifender.core.data.session.RecentAuthOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val A = uuid(1)
private val B = uuid(2)
private val C = uuid(3)

class DeletionRepositoryTest {
    private val api = FakeReviewApi()
    private val store = InMemoryDeletionStore()
    private val archive = FakeArchive().also { a -> a.held.value = listOf(A, B, C).associateWith { source(it) } }
    private val purge = RecordingPurge()
    private var now = 1_000L
    private var ids = 0
    private var recentAuth: suspend () -> RecentAuthOutcome = { RecentAuthOutcome.Confirmed(now) }
    private val passkeys = PasskeyAuthenticator { PasskeyResult.Cancelled }
    private val repo = DeletionRepository(api, store, archive, purge, RecentAuthenticator { recentAuth() }, { now }, { "id-${ids++}" })

    private suspend fun prepared(vararg selected: String): DeletionRepositoryPrepared {
        api.onPrepare = { _, requested -> preparedFor(requested) }
        val out = repo.prepare(selected.toList()) as PrepareOutcome.Ready
        return DeletionRepositoryPrepared(out.review.localId)
    }

    private class DeletionRepositoryPrepared(val id: String)

    @Test
    fun theIntentAndItsKeyAreStoredBeforeTheRequestAndTheReviewIsTheWorkersExactOne() = runBlocking {
        var seen: Pair<DeletionPhase, String>? = null
        api.onPrepare = { key, requested ->
            val row = store.all().single()
            seen = row.phase to row.prepareKey
            assertEquals(listOf(A, B), row.sourceIds)
            assertEquals(key, row.prepareKey)
            preparedFor(requested)
        }
        val out = repo.prepare(listOf(B, A, A)) as PrepareOutcome.Ready
        assertEquals(DeletionPhase.PREPARING to out.review.prepareKey, seen)
        assertEquals(DeletionPhase.PREPARED, out.review.phase)
        assertEquals(DIGEST, out.review.paramsDigest)
        assertEquals(listOf(A, B), api.prepareCalls.single().second)
        assertEquals(2, out.subjects.size)
        // Subjects are shown now and never stored.
        assertTrue(store.writes.none { it.toString().contains("Subject") })
    }

    @Test
    fun emptyOversizedAndUnknownSelectionsSendNothing() = runBlocking {
        assertEquals(PrepareOutcome.Empty, repo.prepare(emptyList()))
        assertEquals(PrepareOutcome.TooMany(50), repo.prepare((1..51).map { uuid(100 + it) }))
        assertEquals(PrepareOutcome.NotOnPhone(listOf(uuid(77))), repo.prepare(listOf(A, uuid(77))))
        assertTrue(api.prepareCalls.isEmpty())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun exactlyFiftyIsAllowed() = runBlocking {
        val fifty = (1..50).map { uuid(200 + it) }
        archive.held.value = fifty.associateWith { source(it) }
        api.onPrepare = { _, requested -> preparedFor(requested) }
        assertTrue(repo.prepare(fifty) is PrepareOutcome.Ready)
        assertEquals(50, api.prepareCalls.single().second.size)
    }

    @Test
    fun aReviewThatDoesNotDescribeTheSelectionIsNeverOfferedForConfirmation() = runBlocking {
        api.onPrepare = { _, _ -> preparedFor(listOf(A, C)) }
        val out = repo.prepare(listOf(A, B))
        assertTrue(out is PrepareOutcome.Mismatch)
        assertEquals(DeletionPhase.REJECTED, store.all().single().phase)
        assertTrue(api.confirmCalls.isEmpty())
    }

    @Test
    fun anUnansweredPreparationIsSentAgainOnlyOnRequestWithTheSameKey() = runBlocking {
        val out = repo.prepare(listOf(A)) as PrepareOutcome.Waiting
        val key = out.review.prepareKey
        assertEquals(1, api.prepareCalls.size)
        api.onPrepare = { _, requested -> preparedFor(requested) }
        val again = repo.retryPrepare(out.review.localId)
        assertTrue(again is PrepareOutcome.Ready)
        assertEquals(listOf(key, key), api.prepareCalls.map { it.first })
    }

    @Test
    fun aRefusedPreparationIsFinalAndSessionLossKeepsTheIntent() = runBlocking {
        api.onPrepare = { _, _ -> PrepareResult.Refused("invalid_request") }
        assertTrue(repo.prepare(listOf(A)) is PrepareOutcome.Refused)
        assertEquals(DeletionPhase.REJECTED, store.all().single().phase)
        api.onPrepare = { _, _ -> PrepareResult.SessionExpired }
        assertEquals(PrepareOutcome.SignIn, repo.prepare(listOf(B)))
        assertEquals(DeletionPhase.PREPARING, store.all().first { it.sourceIds == listOf(B) }.phase)
    }

    @Test
    fun aChangedSelectionDropsTheEarlierReviewAndTheSameSelectionReusesIt() = runBlocking {
        val first = prepared(A, B)
        val reused = repo.prepare(listOf(B, A)) as PrepareOutcome.Ready
        assertEquals(first.id, reused.review.localId)
        assertEquals(1, api.prepareCalls.size)
        val changed = repo.prepare(listOf(A, B, C)) as PrepareOutcome.Ready
        assertNull(store.get(first.id))
        assertEquals(listOf(changed.review.localId), store.all().map { it.localId })
    }

    @Test
    fun aLatePreparationAnswerCannotBringBackAReviewThatWasReplaced() = runBlocking {
        val gate = CompletableDeferred<PrepareResult>()
        api.onPrepare = { _, requested -> if (requested == listOf(A)) gate.await() else preparedFor(requested, uuid(901)) }
        val first = async { repo.prepare(listOf(A)) }
        while (store.all().isEmpty()) kotlinx.coroutines.yield()
        val second = repo.prepare(listOf(B)) as PrepareOutcome.Ready
        gate.complete(preparedFor(listOf(A), uuid(902)))
        assertEquals(PrepareOutcome.Superseded, first.await())
        assertEquals(listOf(second.review.localId), store.all().map { it.localId })
        assertEquals(listOf(B), store.all().single().sourceIds)
    }

    @Test
    fun theConfirmationKeyIsStoredBeforeThePromptAndTheRequestIsMarkedSentBeforeItIsMade() = runBlocking {
        val review = prepared(A)
        var duringPrompt: DeletionPhase? = null
        var keyDuringPrompt: String? = null
        recentAuth = {
            duringPrompt = store.get(review.id)!!.phase
            keyDuringPrompt = store.get(review.id)!!.confirmKey
            RecentAuthOutcome.Confirmed(now)
        }
        var duringRequest: DeletionPhase? = null
        api.onConfirm = { _, key, _ ->
            duringRequest = store.get(review.id)!!.phase
            assertEquals(keyDuringPrompt, key)
            confirmed(listOf(A), uuid(950))
        }
        val out = repo.confirm(review.id, passkeys) as ConfirmOutcome.Deleted
        assertEquals(DeletionPhase.CONFIRMING, duringPrompt)
        assertNotNull(keyDuringPrompt)
        assertEquals(DeletionPhase.SENDING, duringRequest)
        assertEquals(DeletionPhase.SUCCEEDED, out.review.phase)
        assertEquals(7L, out.review.receipt?.ledgerSeq)
        assertEquals(listOf(listOf(A)), purge.purged)
    }

    @Test
    fun aDismissedPasskeyPromptSendsNothingAndKeepsTheSameKeyForNextTime() = runBlocking {
        val review = prepared(A)
        recentAuth = { RecentAuthOutcome.Cancelled }
        assertEquals(ConfirmOutcome.AuthCancelled, repo.confirm(review.id, passkeys))
        assertTrue(api.confirmCalls.isEmpty())
        val key = store.get(review.id)!!.confirmKey
        assertEquals(DeletionPhase.CONFIRMING, store.get(review.id)!!.phase)
        recentAuth = { RecentAuthOutcome.Confirmed(now) }
        api.onConfirm = { _, _, _ -> confirmed(listOf(A), uuid(950)) }
        assertTrue(repo.confirm(review.id, passkeys) is ConfirmOutcome.Deleted)
        assertEquals(key, api.confirmCalls.single().second)
    }

    @Test
    fun aReviewDroppedWhileThePromptIsOpenSendsZeroConfirmations() = runBlocking {
        val review = prepared(A)
        val prompt = CompletableDeferred<RecentAuthOutcome>()
        recentAuth = { prompt.await() }
        val confirming = async { repo.confirm(review.id, passkeys) }
        while (store.get(review.id)?.phase != DeletionPhase.CONFIRMING) kotlinx.coroutines.yield()
        // The owner changes the selection while the prompt is up.
        api.onPrepare = { _, requested -> preparedFor(requested, uuid(901)) }
        val replacement = repo.prepare(listOf(B)) as PrepareOutcome.Ready
        assertNull(store.get(review.id))
        prompt.complete(RecentAuthOutcome.Confirmed(now))
        assertEquals(ConfirmOutcome.Superseded, confirming.await())
        assertTrue("no confirmation was sent for the dropped review", api.confirmCalls.isEmpty())
        assertEquals(listOf(replacement.review.localId), store.all().map { it.localId })
        assertTrue(purge.purged.isEmpty())
    }

    @Test
    fun anEmailThatChangedOrAReviewThatExpiredDuringThePromptSendsZeroConfirmations() = runBlocking {
        val review = prepared(A)
        val prompt = CompletableDeferred<RecentAuthOutcome>()
        recentAuth = { prompt.await() }
        val confirming = async { repo.confirm(review.id, passkeys) }
        while (store.get(review.id)?.phase != DeletionPhase.CONFIRMING) kotlinx.coroutines.yield()
        archive.held.value = mapOf(A to source(A, revision = 2), B to source(B), C to source(C))
        prompt.complete(RecentAuthOutcome.Confirmed(now))
        assertEquals(ConfirmOutcome.Changed, confirming.await())
        assertEquals(DeletionPhase.REJECTED, store.get(review.id)!!.phase)
        assertEquals("selection_changed", store.get(review.id)!!.errorCode)
        assertTrue(api.confirmCalls.isEmpty())

        archive.held.value = listOf(A, B, C).associateWith { source(it) }
        val second = prepared(B)
        val prompt2 = CompletableDeferred<RecentAuthOutcome>()
        recentAuth = { prompt2.await() }
        val confirming2 = async { repo.confirm(second.id, passkeys) }
        while (store.get(second.id)?.phase != DeletionPhase.CONFIRMING) kotlinx.coroutines.yield()
        now = 700_000
        prompt2.complete(RecentAuthOutcome.Confirmed(now))
        assertEquals(ConfirmOutcome.Expired, confirming2.await())
        assertTrue(api.confirmCalls.isEmpty())
    }

    @Test
    fun anExpiredReviewIsNotConfirmedAndANewReviewNeedsANewExplicitRequest() = runBlocking {
        val review = prepared(A)
        now = 600_000
        assertEquals(ConfirmOutcome.Expired, repo.confirm(review.id, passkeys))
        assertTrue(api.confirmCalls.isEmpty())
        api.onPrepare = { _, requested -> preparedFor(requested, uuid(903), expiresAtMs = 1_200_000) }
        val fresh = repo.prepare(listOf(A)) as PrepareOutcome.Ready
        assertTrue(fresh.review.localId != review.id)
        assertNull(store.get(review.id))
    }

    @Test
    fun anEmailChangedOnThePhoneBeforeConfirmingInvalidatesTheReview() = runBlocking {
        val review = prepared(A)
        archive.held.value = mapOf(A to source(A, revision = 3), B to source(B))
        assertEquals(ConfirmOutcome.Changed, repo.confirm(review.id, passkeys))
        assertTrue(api.confirmCalls.isEmpty())
    }

    @Test
    fun aConfirmationWithNoAnswerIsUnknownAndNothingResendsItByItself() = runBlocking {
        val review = prepared(A)
        api.onConfirm = { _, _, _ -> ConfirmResult.NoAnswer("network") }
        val out = repo.confirm(review.id, passkeys) as ConfirmOutcome.Unknown
        assertTrue(out.review.outcomeUnknown)
        assertTrue(purge.purged.isEmpty())
        assertEquals(1, api.confirmCalls.size)
        // A second attempt without a read-back is not allowed: the unknown outcome must be read first.
        assertTrue(repo.confirm(review.id, passkeys) is ConfirmOutcome.NotAllowed)
        assertEquals(1, api.confirmCalls.size)
    }

    @Test
    fun aReadbackThatFindsTheReviewConsumedSettlesItFromTheOperationAndPurgesOnce() = runBlocking {
        val review = prepared(A, B)
        api.onConfirm = { _, _, _ -> ConfirmResult.NoAnswer("network") }
        repo.confirm(review.id, passkeys)
        api.onReadReview = { readback(ReviewState.CONSUMED, operationId = uuid(950), operationState = OperationState.SUCCEEDED) }
        api.onReadOperation = { succeededOperation(listOf(A, B), uuid(950)) }
        val out = repo.readback(review.id) as ReadbackOutcome.Deleted
        assertEquals(DeletionPhase.SUCCEEDED, out.review.phase)
        assertEquals(listOf(listOf(A, B)), purge.purged)
        // Reading again changes nothing and purges nothing more.
        assertEquals(ReadbackOutcome.NothingToRead, repo.readback(review.id))
        assertEquals(1, purge.purged.size)
        assertEquals(1, api.confirmCalls.size)
    }

    @Test
    fun aNotFoundReadbackIsNotProofOfNoEffect() = runBlocking {
        val review = prepared(A)
        api.onConfirm = { _, _, _ -> ConfirmResult.NoAnswer("network") }
        repo.confirm(review.id, passkeys)
        api.onReadReview = { ReviewRead.NotKnown }
        val out = repo.readback(review.id) as ReadbackOutcome.Unsettled
        assertEquals(UnsettledReason.NOT_KNOWN, out.reason)
        assertEquals(DeletionPhase.SENDING, store.get(review.id)!!.phase)
        assertTrue(purge.purged.isEmpty())
        api.onReadReview = { ReviewRead.Unavailable("network") }
        assertEquals(UnsettledReason.UNAVAILABLE, (repo.readback(review.id) as ReadbackOutcome.Unsettled).reason)
        assertEquals(DeletionPhase.SENDING, store.get(review.id)!!.phase)
    }

    @Test
    fun anOpenReviewIsOnlyAPointInTimeAndMayBeConfirmedAgainWithTheSameKeyOnTheOwnersRequest() = runBlocking {
        val review = prepared(A)
        api.onConfirm = { _, _, _ -> ConfirmResult.NoAnswer("network") }
        repo.confirm(review.id, passkeys)
        val key = store.get(review.id)!!.confirmKey
        api.onReadReview = { readback(ReviewState.OPEN) }
        assertEquals(UnsettledReason.OPEN, (repo.readback(review.id) as ReadbackOutcome.Unsettled).reason)
        assertEquals(DeletionPhase.SENDING, store.get(review.id)!!.phase)
        assertTrue(repo.confirmable(store.get(review.id)!!))
        api.onConfirm = { _, _, _ -> confirmed(listOf(A), uuid(950)) }
        assertTrue(repo.confirm(review.id, passkeys) is ConfirmOutcome.Deleted)
        assertEquals(listOf(key, key), api.confirmCalls.map { it.second })
    }

    @Test
    fun anExpiredReviewIsSettledAsUnusedOnlyAfterAMarginPastItsExpiry() = runBlocking {
        val review = prepared(A)
        api.onConfirm = { _, _, _ -> ConfirmResult.NoAnswer("network") }
        repo.confirm(review.id, passkeys)
        api.onReadReview = { readback(ReviewState.EXPIRED, expiresAtMs = 600_000) }
        now = 610_000
        assertEquals(UnsettledReason.EXPIRED_RECENTLY, (repo.readback(review.id) as ReadbackOutcome.Unsettled).reason)
        assertEquals(DeletionPhase.SENDING, store.get(review.id)!!.phase)
        now = 700_000
        val out = repo.readback(review.id) as ReadbackOutcome.Unused
        assertEquals(DeletionPhase.REJECTED, out.review.phase)
        assertEquals("review_expired_unused", out.review.errorCode)
        assertTrue(purge.purged.isEmpty())
    }

    @Test
    fun aReadbackThatDescribesAnotherReviewSettlesNothing() = runBlocking {
        val review = prepared(A)
        api.onConfirm = { _, _, _ -> ConfirmResult.NoAnswer("network") }
        repo.confirm(review.id, passkeys)
        api.onReadReview = { readback(ReviewState.CONSUMED, reviewId = uuid(999), operationId = uuid(950), operationState = OperationState.SUCCEEDED) }
        assertEquals(UnsettledReason.MISMATCH, (repo.readback(review.id) as ReadbackOutcome.Unsettled).reason)
        api.onReadReview = { readback(ReviewState.CONSUMED, operationId = uuid(950), operationState = OperationState.SUCCEEDED) }
        // An operation for other sources, or one that is not yet succeeded, is not a settlement either.
        api.onReadOperation = { succeededOperation(listOf(B), uuid(950)) }
        assertEquals(UnsettledReason.MISMATCH, (repo.readback(review.id) as ReadbackOutcome.Unsettled).reason)
        api.onReadOperation = { ReviewRead.Ok(Operation(uuid(950), uuid(900), OperationState.UNCERTAIN, ReviewEffect.ARCHIVE_DELETE, listOf(A), null, null, null, 0, null, null)) }
        assertEquals(UnsettledReason.OPERATION_UNSETTLED, (repo.readback(review.id) as ReadbackOutcome.Unsettled).reason)
        assertEquals(DeletionPhase.SENDING, store.get(review.id)!!.phase)
        assertTrue(purge.purged.isEmpty())
    }

    @Test
    fun aStaleReadbackCannotOverwriteAReceiptThatSettledInTheMeantime() = runBlocking {
        val review = prepared(A)
        api.onConfirm = { _, _, _ -> ConfirmResult.NoAnswer("network") }
        repo.confirm(review.id, passkeys)
        val firstRead = CompletableDeferred<ReviewRead<com.digifender.adminifender.core.data.network.ReviewReadback>>()
        var call = 0
        api.onReadReview = {
            if (call++ == 0) firstRead.await() else readback(ReviewState.CONSUMED, operationId = uuid(950), operationState = OperationState.SUCCEEDED)
        }
        api.onReadOperation = { succeededOperation(listOf(A), uuid(950)) }
        val stale = async { repo.readback(review.id) }
        while (api.readReviewCalls.isEmpty()) kotlinx.coroutines.yield()
        // Another read-back settles the deletion while the first is still waiting for its answer.
        assertTrue(repo.readback(review.id) is ReadbackOutcome.Deleted)
        firstRead.complete(readback(ReviewState.OPEN))
        val late = stale.await()
        assertTrue("the late read reports the settlement, not its own stale view: $late", late is ReadbackOutcome.Deleted)
        val row = store.get(review.id)!!
        assertEquals(DeletionPhase.SUCCEEDED, row.phase)
        assertEquals(7L, row.receipt?.ledgerSeq)
        assertEquals(listOf(listOf(A)), purge.purged)
    }

    @Test
    fun aConfirmationAnswerThatArrivesAfterAReadbackSettledItChangesNothing() = runBlocking {
        val review = prepared(A)
        val answer = CompletableDeferred<ConfirmResult>()
        api.onConfirm = { _, _, _ -> answer.await() }
        val confirming = async { repo.confirm(review.id, passkeys) }
        while (api.confirmCalls.isEmpty()) kotlinx.coroutines.yield()
        // The in-flight confirmation is not read back from under itself.
        assertEquals(ReadbackOutcome.NothingToRead, repo.readback(review.id))
        answer.complete(confirmed(listOf(A), uuid(950)))
        assertTrue(confirming.await() is ConfirmOutcome.Deleted)
        assertEquals(listOf(listOf(A)), purge.purged)
    }

    @Test
    fun signOutDuringAConfirmationDiscardsItsAnswerAndPurgesNothing() = runBlocking {
        val review = prepared(A)
        val answer = CompletableDeferred<ConfirmResult>()
        api.onConfirm = { _, _, _ -> answer.await() }
        val confirming = async { repo.confirm(review.id, passkeys) }
        while (api.confirmCalls.isEmpty()) kotlinx.coroutines.yield()
        repo.invalidate()
        store.rows.value = emptyMap()
        answer.complete(confirmed(listOf(A), uuid(950)))
        assertTrue(confirming.await() !is ConfirmOutcome.Deleted)
        assertTrue(store.all().isEmpty())
        assertTrue(purge.purged.isEmpty())
    }

    @Test
    fun definiteRefusalsAreRecordedAndSessionLossKeepsTheReview() = runBlocking {
        val review = prepared(A)
        api.onConfirm = { _, _, _ -> ConfirmResult.Rejected("stale_review", 409) }
        assertTrue(repo.confirm(review.id, passkeys) is ConfirmOutcome.Rejected)
        assertEquals("stale_review", store.get(review.id)!!.errorCode)
        assertEquals(DeletionPhase.REJECTED, store.get(review.id)!!.phase)

        val second = prepared(B)
        api.onConfirm = { _, _, _ -> ConfirmResult.SessionExpired }
        assertEquals(ConfirmOutcome.SignIn, repo.confirm(second.id, passkeys))
        assertEquals(DeletionPhase.CONFIRMING, store.get(second.id)!!.phase)

        api.onConfirm = { _, _, _ -> ConfirmResult.RecentAuthRequired }
        assertTrue(repo.confirm(second.id, passkeys) is ConfirmOutcome.NotCompleted)
        assertEquals(DeletionPhase.CONFIRMING, store.get(second.id)!!.phase)

        api.onConfirm = { _, _, _ -> ConfirmResult.NotCompleted("ledger_unavailable") }
        assertTrue(repo.confirm(second.id, passkeys) is ConfirmOutcome.NotCompleted)
        assertEquals(DeletionPhase.CONFIRMING, store.get(second.id)!!.phase)
        // The same stored key was used for every attempt of this review.
        assertEquals(1, api.confirmCalls.drop(1).map { it.second }.distinct().size)
    }

    @Test
    fun anAlreadyConsumedAnswerIsResolvedThroughTheOperationNotAssumed() = runBlocking {
        val review = prepared(A)
        api.onConfirm = { _, _, _ -> ConfirmResult.AlreadyConsumed(uuid(950)) }
        api.onReadReview = { readback(ReviewState.CONSUMED, operationId = uuid(950), operationState = OperationState.SUCCEEDED) }
        api.onReadOperation = { succeededOperation(listOf(A), uuid(950)) }
        assertTrue(repo.confirm(review.id, passkeys) is ConfirmOutcome.Deleted)
        assertEquals(listOf(listOf(A)), purge.purged)
    }

    @Test
    fun cleanupIsReadSeparatelyAndNeverClaimsMoreThanTheLiveArchive() = runBlocking {
        val review = prepared(A)
        api.onConfirm = { _, _, _ -> confirmed(listOf(A), uuid(950)) }
        repo.confirm(review.id, passkeys)
        assertNull(store.get(review.id)!!.cleanup)
        val cleanup = Cleanup(CleanupState.PARTIAL, 1, 0, 0, 0)
        api.onReadOperation = { ReviewRead.Ok(Operation(uuid(950), uuid(900), OperationState.SUCCEEDED, ReviewEffect.ARCHIVE_DELETE, listOf(A), null, receipt(1), null, 0, 0, cleanup)) }
        assertTrue(repo.refreshCleanup(review.id))
        assertEquals(CleanupState.PARTIAL, store.get(review.id)!!.cleanup?.state)
        assertNotNull(store.get(review.id)!!.cleanupCheckedAtMs)
    }

    @Test
    fun trackingStopsOnlyOnTheOwnersRequestAndFinishedRowsCanBeDismissed() = runBlocking {
        val review = prepared(A)
        api.onConfirm = { _, _, _ -> ConfirmResult.NoAnswer("network") }
        repo.confirm(review.id, passkeys)
        assertFalse("an unknown outcome is not dismissed silently", repo.dismiss(review.id))
        repo.stopTracking(review.id)
        assertEquals(DeletionPhase.STOPPED, store.get(review.id)!!.phase)
        assertTrue(repo.dismiss(review.id))
    }

    @Test
    fun startingTheAppRepeatsThePurgeOfASettledDeletionAndSendsNothing() = runBlocking {
        val review = prepared(A)
        api.onConfirm = { _, _, _ -> confirmed(listOf(A), uuid(950)) }
        repo.confirm(review.id, passkeys)
        purge.purged.clear()
        val requests = api.prepareCalls.size + api.confirmCalls.size + api.readReviewCalls.size
        repo.recover()
        assertEquals(listOf(listOf(A)), purge.purged)
        assertEquals(requests, api.prepareCalls.size + api.confirmCalls.size + api.readReviewCalls.size)
    }
}
