package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.*
import com.digifender.adminifender.core.data.repository.PreparedDeletion
import com.digifender.adminifender.core.data.repository.ReviewRepository
import com.digifender.adminifender.core.data.repository.deletionDigest
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

private const val SOURCE = "00000000-0000-4000-9000-000000000001"
private const val CONCERN = "00000000-0000-4000-9000-000000000002"
private const val REVIEW = "00000000-0000-4000-9000-000000000003"
private const val OPERATION = "00000000-0000-4000-9000-000000000004"
private const val NOW = 1_800_000_000_000L

private fun review() = DeleteReview(
    REVIEW, "archive.delete", DeleteParams(listOf(SOURCE)),
    deletionDigest(DeleteParams(listOf(SOURCE)), DeletePreconditions(mapOf(SOURCE to 3), mapOf(CONCERN to 7))),
    DeletePreconditions(mapOf(SOURCE to 3), mapOf(CONCERN to 7)),
    DeletePreview(listOf(ReviewedSource(SOURCE, "Synthetic bill", Instant.ofEpochMilli(NOW - 1000).toString(), "normal")), 2, listOf(CONCERN), emptyList(), listOf("raw message"), 1),
    Instant.ofEpochMilli(NOW + 600_000).toString(), true,
)

private class Reviews : ReviewApi {
    var prepared = review()
    val confirmations = mutableListOf<Triple<String, String, String>>()
    var response: TransferResult<ConfirmedDeletion> = TransferResult.Ok(ConfirmedDeletion(OPERATION, "succeeded", DeletionReceipt(10, "a".repeat(64), 1)))
    var readback: TransferResult<DeletionOperation> = TransferResult.Ok(DeletionOperation(OPERATION, REVIEW, "succeeded", DeletionIntent("archive.delete", DeleteParams(listOf(SOURCE))), DeletionReceipt(10, "a".repeat(64), 1)))
    override suspend fun reviewDeletion(sourceIds: List<String>, key: String): TransferResult<DeleteReview> = TransferResult.Ok(prepared)
    override suspend fun confirmDeletion(reviewId: String, digest: String, key: String): TransferResult<ConfirmedDeletion> {
        confirmations += Triple(reviewId, digest, key)
        return response
    }
    override suspend fun operation(id: String) = readback
}

class ReviewRepositoryTest {
    private val api = Reviews()
    private var now = NOW
    private val removed = mutableListOf<String>()
    private val repo = ReviewRepository(api, { now }, { ids, _ -> removed += ids })
    private suspend fun prepare(): PreparedDeletion = (repo.prepare(listOf(SOURCE)) as TransferResult.Ok).value

    @Test
    fun exactVerifiedReviewAndReceiptDeleteOnlyReviewedSources() = runTest {
        val prepared = prepare()
        val result = repo.confirm(prepared, now)
        assertTrue(result is TransferResult.Ok)
        assertEquals(listOf(SOURCE), removed)
        assertEquals(REVIEW, api.confirmations.single().first)
        assertEquals(prepared.review.paramsDigest, api.confirmations.single().second)
    }

    @Test
    fun substitutedScopeDigestRevisionsCountsAndRecentAuthFlagAreRejected() = runTest {
        val good = review()
        val altered = listOf(
            good.copy(params = DeleteParams(listOf(CONCERN))),
            good.copy(paramsDigest = "b".repeat(64)),
            good.copy(preconditions = good.preconditions.copy(sources = mapOf(SOURCE to 4))),
            good.copy(preview = good.preview.copy(expectedCount = 2)),
            good.copy(preview = good.preview.copy(sources = good.preview.sources + good.preview.sources)),
            good.copy(preview = good.preview.copy(concernsDetached = listOf(CONCERN))),
            good.copy(requiresRecentAuth = false),
            good.copy(effect = "device.revoke"),
        )
        for (candidate in altered) {
            api.prepared = candidate
            assertEquals(TransferResult.Rejected("invalid_review"), repo.prepare(listOf(SOURCE)))
        }
        assertTrue(api.confirmations.isEmpty())
    }

    @Test
    fun reviewExpiryIsRecheckedAfterAuthenticationAndStaleAuthenticationCannotSend() = runTest {
        val prepared = prepare()
        now += 600_000
        assertEquals(TransferResult.Rejected("review_expired"), repo.confirm(prepared, now))
        now = NOW
        assertEquals(TransferResult.Rejected("recent_auth_required"), repo.confirm(prepared, now - 300_001))
        assertEquals(TransferResult.Rejected("recent_auth_required"), repo.confirm(prepared, now + 1))
        assertTrue(api.confirmations.isEmpty())
        assertTrue(removed.isEmpty())
    }

    @Test
    fun unknownResponseRetriesExactIdentityEvenAfterReviewExpiry() = runTest {
        val prepared = prepare()
        api.response = TransferResult.Unavailable("network")
        assertTrue(repo.confirm(prepared, now) is TransferResult.Unavailable)
        now += 600_001
        api.response = TransferResult.Ok(ConfirmedDeletion(OPERATION, "succeeded", DeletionReceipt(10, "a".repeat(64), 1)))
        assertTrue(repo.confirm(prepared, now) is TransferResult.Ok)
        assertEquals(api.confirmations[0], api.confirmations[1])
        assertEquals(listOf(SOURCE), removed)
    }

    @Test
    fun consumedReviewNeedsMatchingOperationIntentAndReceiptBeforeLocalPurge() = runTest {
        val prepared = prepare()
        api.response = TransferResult.Rejected("review_consumed", OPERATION)
        val valid = (api.readback as TransferResult.Ok).value
        api.readback = TransferResult.Ok(valid.copy(reviewId = SOURCE))
        assertEquals(TransferResult.Unavailable("unverified_operation"), repo.confirm(prepared, now))
        assertTrue(removed.isEmpty())
        api.readback = TransferResult.Ok(valid)
        assertTrue(repo.confirm(prepared, now) is TransferResult.Ok)
        assertEquals(listOf(SOURCE), removed)
    }

    @Test
    fun malformedReceiptAndLedgerOutageNeverClaimDeletion() = runTest {
        val prepared = prepare()
        api.response = TransferResult.Ok(ConfirmedDeletion(OPERATION, "succeeded", DeletionReceipt(10, "a".repeat(64), 2)))
        assertEquals(TransferResult.Unavailable("unverified_operation"), repo.confirm(prepared, now))
        api.response = TransferResult.Unavailable("ledger_unavailable")
        assertEquals(TransferResult.Unavailable("ledger_unavailable"), repo.confirm(prepared, now))
        assertTrue(removed.isEmpty())
    }

    @Test
    fun invalidAndRepeatedTargetIdsCannotCreateAReview() = runTest {
        assertEquals(TransferResult.Rejected("invalid_request"), repo.prepare(listOf(SOURCE, SOURCE)))
        assertEquals(TransferResult.Rejected("invalid_request"), repo.prepare(listOf("../source")))
        assertEquals(TransferResult.Rejected("invalid_request"), repo.prepare(emptyList()))
    }
}
