package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.*
import com.digifender.adminifender.core.data.repository.*
import java.io.File
import java.io.OutputStream
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val JOURNAL_SOURCE = "00000000-0000-4000-9000-000000000051"
private const val JOURNAL_REVIEW = "00000000-0000-4000-9000-000000000052"
private const val JOURNAL_OPERATION = "00000000-0000-4000-9000-000000000053"
private const val JOURNAL_NOW = 1_800_000_000_000L

private class JournalServer : ReviewApi, ExportApi, ArchiveBytesApi {
    val createKeys = mutableListOf<String>()
    val confirmKeys = mutableListOf<String>()
    val exportKeys = mutableListOf<String>()
    var noAnswer = false
    var now = JOURNAL_NOW
    val params = DeleteParams(listOf(JOURNAL_SOURCE))
    val preconditions = DeletePreconditions(mapOf(JOURNAL_SOURCE to 1), emptyMap())
    val review = DeleteReview(JOURNAL_REVIEW, "archive.delete", params, deletionDigest(params, preconditions), preconditions,
        DeletePreview(listOf(ReviewedSource(JOURNAL_SOURCE, "Synthetic subject marker", "2026-10-07T12:00:00Z", "normal")), 0, emptyList(), emptyList(), emptyList(), 1),
        Instant.ofEpochMilli(JOURNAL_NOW + 600_000).toString(), true)
    override suspend fun reviewDeletion(sourceIds: List<String>, key: String): TransferResult<DeleteReview> {
        createKeys += key
        return if (noAnswer) TransferResult.Unavailable("network") else TransferResult.Ok(review)
    }
    override suspend fun confirmDeletion(reviewId: String, digest: String, key: String): TransferResult<ConfirmedDeletion> {
        confirmKeys += key
        return if (noAnswer) TransferResult.Unavailable("network") else TransferResult.Ok(ConfirmedDeletion(JOURNAL_OPERATION, "succeeded", DeletionReceipt(1, "b".repeat(64), 1)))
    }
    override suspend fun operation(id: String): TransferResult<DeletionOperation> = error("not used")
    override suspend fun createExport(key: String): TransferResult<ArchiveExport> {
        exportKeys += key
        return if (noAnswer) TransferResult.Unavailable("network") else TransferResult.Ok(ArchiveExport(JOURNAL_OPERATION, "pending", 12, Instant.ofEpochMilli(JOURNAL_NOW + 600_000).toString()))
    }
    override suspend fun export(id: String): TransferResult<ArchiveExport> = error("not used")
    override suspend fun attachment(id: String, hash: String, size: Long, output: OutputStream): TransferResult<Long> = error("not used")
    override suspend fun exportContent(id: String, output: OutputStream): TransferResult<Long> = error("not used")
}

class TransferJournalTest {
    @get:Rule val folder = TemporaryFolder()
    private val api = JournalServer()
    private fun journal() = FileTransferJournal(File(folder.root, "journal"))
    private fun reviews(journal: TransferJournal = journal()) = ReviewRepository(api, { api.now }, { _, _ -> }, journal = journal)

    @Test
    fun unknownReviewCreationReusesPersistedKeyAfterProcessRestart() = runTest {
        api.noAnswer = true
        assertTrue(reviews().prepare(listOf(JOURNAL_SOURCE)) is TransferResult.Unavailable)
        api.noAnswer = false
        assertTrue(reviews().prepare(listOf(JOURNAL_SOURCE)) is TransferResult.Ok)
        assertEquals(2, api.createKeys.size)
        assertEquals(api.createKeys.first(), api.createKeys.last())
        assertTrue(api.confirmKeys.isEmpty())
    }

    @Test
    fun restoredUnknownConfirmationNeverAutosendsAndExplicitReadbackKeepsExactKeyAfterExpiry() = runTest {
        val original = reviews()
        val prepared = (original.prepare(listOf(JOURNAL_SOURCE)) as TransferResult.Ok).value
        api.noAnswer = true
        assertTrue(original.confirm(prepared, api.now) is TransferResult.Unavailable)
        api.now += 700_000
        val restarted = reviews()
        val restored = (restarted.prepare(listOf(JOURNAL_SOURCE)) as TransferResult.Ok).value
        assertTrue(restored.needsReadback)
        assertEquals(1, api.createKeys.size)
        assertEquals(1, api.confirmKeys.size)
        api.noAnswer = false
        assertTrue(restarted.confirm(restored, api.now) is TransferResult.Ok)
        assertEquals(api.confirmKeys.first(), api.confirmKeys.last())
        assertNull(journal().deletion(listOf(JOURNAL_SOURCE)))
    }

    @Test
    fun tombstoneScrubsSubmittedSubjectButKeepsContentFreeReadbackIdentity() = runTest {
        val journal = journal()
        val repo = reviews(journal)
        val prepared = (repo.prepare(listOf(JOURNAL_SOURCE)) as TransferResult.Ok).value
        api.noAnswer = true
        repo.confirm(prepared, api.now)
        journal.invalidateSources(listOf(JOURNAL_SOURCE))
        val persisted = requireNotNull(journal().deletion(listOf(JOURNAL_SOURCE)))
        assertTrue(persisted.submitted)
        assertTrue(persisted.invalidated)
        assertNull(persisted.review!!.preview.sources.single().subject)
        assertEquals(prepared.review.paramsDigest, persisted.review.paramsDigest)
        assertFalse(File(folder.root, "journal").walkTopDown().filter { it.isFile }.any { it.readText().contains("Synthetic subject marker") })
        val restored = (reviews().prepare(listOf(JOURNAL_SOURCE)) as TransferResult.Ok).value
        assertTrue(restored.needsReadback)
        assertEquals(1, api.confirmKeys.size)
    }

    @Test
    fun signOutFencesAReviewPreparedBeforeIt() = runTest {
        val journal = journal()
        val repo = reviews(journal)
        val prepared = (repo.prepare(listOf(JOURNAL_SOURCE)) as TransferResult.Ok).value
        journal.clear()
        assertEquals(TransferResult.Unavailable("journal_unavailable"), repo.confirm(prepared, api.now))
        assertTrue(api.confirmKeys.isEmpty())
        assertNull(journal().deletion(listOf(JOURNAL_SOURCE)))
    }

    @Test
    fun exportCreationAndSnapshotIdentitySurviveRestartWithoutAutomaticDispatch() = runTest {
        fun exports() = ExportRepository(api, api, PrivateDownloadStore(File(folder.root, "downloads")), { api.now }, journal())
        val original = exports()
        val request = original.request()
        api.noAnswer = true
        assertTrue(original.create(request) is TransferResult.Unavailable)
        val restarted = exports()
        val restored = (restarted.restore() as TransferResult.Ok).value
        assertTrue(restored.unknownCreation)
        assertEquals(1, api.exportKeys.size)
        api.noAnswer = false
        assertTrue(restarted.create(restored.request) is TransferResult.Ok)
        assertEquals(api.exportKeys.first(), api.exportKeys.last())
        val finalRestore = (exports().restore() as TransferResult.Ok).value
        assertFalse(finalRestore.unknownCreation)
        assertEquals(JOURNAL_OPERATION, finalRestore.export!!.id)
        assertEquals(2, api.exportKeys.size)
    }
}
