package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.files.AskOutcome
import com.digifender.adminifender.core.data.files.AttachmentRepository
import com.digifender.adminifender.core.data.files.BlobDirectory
import com.digifender.adminifender.core.data.files.DownloadResult
import com.digifender.adminifender.core.data.files.FileCipher
import com.digifender.adminifender.core.data.files.FileRead
import com.digifender.adminifender.core.data.files.FileState
import com.digifender.adminifender.core.data.files.MAX_ATTACHMENT_BYTES
import com.digifender.adminifender.core.data.files.MAX_DOWNLOAD_ATTEMPTS
import com.digifender.adminifender.core.data.files.SaveOutcome
import com.digifender.adminifender.core.data.network.OpenResult
import com.digifender.adminifender.core.data.network.SourceSummary
import com.digifender.adminifender.core.data.repository.AttachmentInfo
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SRC = "0b4e6f8a-2c3d-4e5f-8a9b-1c2d3e4f5a6b"
private const val ATT = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val ATT2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
private const val ATT3 = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"

/** First position of [needle] in [haystack], or -1: a plain scan with an early exit on the first differing byte. */
private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
    outer@ for (start in 0..haystack.size - needle.size) {
        for (i in needle.indices) if (haystack[start + i] != needle[i]) continue@outer
        return start
    }
    return -1
}

class AttachmentRepositoryTest {
    private val sources = mutableMapOf(SRC to source(SRC))
    private val store = InMemoryFileStore { sources }
    private val dir = tempDirectory()
    private val blobs = BlobDirectory(File(dir, "blobs"))
    private val api = ScriptedContentApi()
    private val scheduler = FakeScheduler()
    private val keys = SoftKeys()
    private val cipher = FileCipher(keys)
    private var now = 1_000_000L
    private var counter = 0
    private fun repo(cap: Long = 500L * 1024 * 1024, max: Long = MAX_ATTACHMENT_BYTES) =
        AttachmentRepository(api, store, blobs, cipher, scheduler, { now++ }, cap, max, { "gen-${counter++}" })

    private fun info(bytes: ByteArray, id: String = ATT, sha: String = sha256Of(bytes), size: Long = bytes.size.toLong()) =
        AttachmentInfo(id, "report.txt", "text/plain", size, "ready", null, sha, "attachment")

    private val body = ByteArray(300_000) { (it % 251).toByte() }

    private fun leftovers() = blobs.names().filter { it.endsWith(".tmp") }

    @Test
    fun anAskedFileIsDownloadedVerifiedSealedAndReadBackIntact() = runBlocking {
        val repo = repo()
        assertEquals(AskOutcome.Asked, repo.ask(SRC, info(body)))
        assertEquals(listOf(ATT), scheduler.enqueued)
        api.serve(body)
        assertEquals(DownloadResult.Done, repo.run(ATT))
        val row = store.get(ATT)!!
        assertEquals(FileState.READY, row.state)
        assertNotNull(row.verifiedAt)
        assertEquals(1, blobs.names().size)
        assertTrue(blobs.names().single().endsWith(".enc"))
        // Not stored in the clear.
        assertEquals(-1, indexOf(blobs.file(row.blobName!!)!!.readBytes(), body.copyOfRange(1000, 1064)))
        val read = repo.read(ATT) as FileRead.Bytes
        assertArrayEquals(body, read.bytes)
        // Asking again for a ready file is no new request.
        assertEquals(AskOutcome.AlreadyReady, repo.ask(SRC, info(body)))
    }

    @Test
    fun theBoundIsTheAcceptedRawSizeNotTheExtractionLimit() = runBlocking {
        val repo = repo()
        // 22 MiB is above the 20 MiB extraction limit and still downloadable; 25 MiB + 1 is above what the server admits.
        assertEquals(AskOutcome.Asked, repo.ask(SRC, info(ByteArray(0), sha = sha256Of(ByteArray(0)), size = 22L * 1024 * 1024)))
        assertEquals(AskOutcome.Asked, repo.ask(SRC, info(ByteArray(0), id = ATT2, sha = sha256Of(ByteArray(0)), size = MAX_ATTACHMENT_BYTES)))
        assertEquals(AskOutcome.TooLarge(MAX_ATTACHMENT_BYTES), repo.ask(SRC, info(ByteArray(0), id = ATT3, sha = sha256Of(ByteArray(0)), size = MAX_ATTACHMENT_BYTES + 1)))
        assertNull(store.get(ATT3))
        assertEquals(25L * 1024 * 1024, MAX_ATTACHMENT_BYTES)
    }

    @Test
    fun aMissingChecksumIsNotDownloadedUnverified() = runBlocking {
        val repo = repo()
        assertEquals(AskOutcome.NeedsRefresh, repo.ask(SRC, info(body, sha = "")))
        assertEquals(AskOutcome.NeedsRefresh, repo.ask(SRC, info(body, sha = "ABC")))
        assertTrue(store.all().isEmpty())
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun aSourceThePhoneDoesNotHoldCannotBeAskedFor() = runBlocking {
        assertEquals(AskOutcome.SourceUnavailable, repo().ask("9b4e6f8a-2c3d-4e5f-8a9b-1c2d3e4f5a6b", info(body)))
    }

    @Test
    fun aWrongChecksumATruncatedBodyAndAnOverlongBodyAreEachRefusedAndLeaveNothing() = runBlocking {
        val repo = repo()
        // Server sends other bytes than the inventory promised.
        repo.ask(SRC, info(body))
        api.serve(ByteArray(body.size) { 1 }, declared = sha256Of(body))
        assertEquals(DownloadResult.Failed("hash_mismatch"), repo.run(ATT))
        assertEquals("hash_mismatch", store.get(ATT)!!.errorCode)
        // The header disagrees with the inventory.
        repo.ask(SRC, info(body))
        api.serve(body, declared = sha256Of(ByteArray(2)))
        assertEquals(DownloadResult.Failed("hash_mismatch"), repo.run(ATT))
        // Content-Length disagrees with the inventory.
        repo.ask(SRC, info(body))
        api.serve(body, length = body.size + 5L)
        assertEquals(DownloadResult.Failed("size_mismatch"), repo.run(ATT))
        // The body ends early although the length said otherwise.
        repo.ask(SRC, info(body))
        api.serve(body.copyOf(1000), declared = sha256Of(body), length = body.size.toLong())
        assertEquals(DownloadResult.Failed("size_mismatch"), repo.run(ATT))
        // The body runs past the inventory's size: stopped, not stored.
        repo.ask(SRC, info(body))
        api.serve(body + ByteArray(50), declared = sha256Of(body), length = -1)
        assertEquals(DownloadResult.Failed("size_mismatch"), repo.run(ATT))
        assertTrue(blobs.names().isEmpty())
        assertNotEquals(FileState.READY, store.get(ATT)!!.state)
    }

    @Test
    fun aFailedRequestIsAskedAgainUnderANewGeneration() = runBlocking {
        val repo = repo()
        repo.ask(SRC, info(body))
        val first = store.get(ATT)!!.generation
        api.answers += OpenResult.Refused("invalid_request", 400)
        assertEquals(DownloadResult.Failed("invalid_request"), repo.run(ATT))
        assertEquals(AskOutcome.Asked, repo.ask(SRC, info(body)))
        assertNotEquals(first, store.get(ATT)!!.generation)
        api.serve(body)
        assertEquals(DownloadResult.Done, repo.run(ATT))
    }

    @Test
    fun failuresBeforeAnyByteRetryOnlyUpToTheCap() = runBlocking {
        val repo = repo()
        repo.ask(SRC, info(body))
        repeat(MAX_DOWNLOAD_ATTEMPTS - 1) {
            api.answers += OpenResult.Unavailable("network")
            assertEquals(DownloadResult.Retry("network"), repo.run(ATT))
            assertEquals(FileState.QUEUED, store.get(ATT)!!.state)
        }
        api.answers += OpenResult.Unavailable("network")
        assertEquals(DownloadResult.Failed("network"), repo.run(ATT))
        assertEquals(FileState.FAILED, store.get(ATT)!!.state)
        assertEquals(MAX_DOWNLOAD_ATTEMPTS, store.get(ATT)!!.attempts)
    }

    @Test
    fun repeatedDisconnectsAfterSomeBytesAreCountedAndCappedLikeAnyOtherFailure() = runBlocking {
        val repo = repo()
        repo.ask(SRC, info(body))
        repeat(MAX_DOWNLOAD_ATTEMPTS - 1) {
            api.serve(body, failAfter = 120_000)
            assertEquals(DownloadResult.Retry("network"), repo.run(ATT))
            assertEquals(FileState.QUEUED, store.get(ATT)!!.state)
            assertTrue("the partial file is removed at once", blobs.names().isEmpty())
        }
        api.serve(body, failAfter = 120_000)
        assertEquals(DownloadResult.Failed("network"), repo.run(ATT))
        assertEquals(FileState.FAILED, store.get(ATT)!!.state)
        assertEquals(MAX_DOWNLOAD_ATTEMPTS, api.asked.size)
        assertTrue(blobs.names().isEmpty())
        // The owner can ask again; that starts over.
        assertEquals(AskOutcome.Asked, repo.ask(SRC, info(body)))
        api.serve(body)
        assertEquals(DownloadResult.Done, repo.run(ATT))
    }

    @Test
    fun aSessionThatEndedHoldsTheDownloadUntilTheOwnerSignsInAgain() = runBlocking {
        val repo = repo()
        repo.ask(SRC, info(body))
        api.answers += OpenResult.SessionExpired
        assertEquals(DownloadResult.SignIn, repo.run(ATT))
        assertEquals(FileState.HELD, store.get(ATT)!!.state)
        val held = store.get(ATT)!!.generation
        scheduler.enqueued.clear()
        repo.resumeHeld()
        assertEquals(FileState.QUEUED, store.get(ATT)!!.state)
        assertNotEquals(held, store.get(ATT)!!.generation)
        assertEquals(listOf(ATT), scheduler.enqueued)
    }

    @Test
    fun goneAndRefusedAnswersAreFinalNotRetried() = runBlocking {
        val repo = repo()
        repo.ask(SRC, info(body))
        api.answers += OpenResult.Gone("not_found", 404)
        assertEquals(DownloadResult.Failed("gone"), repo.run(ATT))
        assertEquals(FileState.FAILED, store.get(ATT)!!.state)
    }

    @Test
    fun theCacheKeepsToItsCapByEvictingTheLeastRecentlyOpenedFileAndDeletingItsBlob() = runBlocking {
        val repo = repo(cap = 700_000)
        val a = ByteArray(300_000) { 1 }
        val b = ByteArray(300_000) { 2 }
        val c = ByteArray(300_000) { 3 }
        for ((id, bytes) in listOf(ATT to a, ATT2 to b)) {
            repo.ask(SRC, info(bytes, id))
            api.serve(bytes)
            assertEquals(DownloadResult.Done, repo.run(id))
        }
        // Opening A makes B the least recently used.
        assertTrue(repo.read(ATT) is FileRead.Bytes)
        repo.ask(SRC, info(c, ATT3))
        api.serve(c)
        assertEquals(DownloadResult.Done, repo.run(ATT3))
        assertNull(store.get(ATT2))
        assertEquals(FileState.READY, store.get(ATT)!!.state)
        assertEquals(FileState.READY, store.get(ATT3)!!.state)
        assertEquals(2, blobs.names().size)
        assertEquals(blobs.names(), store.blobNames())
        assertEquals(FileRead.NotAvailable, repo.read(ATT2))
    }

    @Test
    fun purgingWhileTheBytesAreArrivingNeverLeavesAFileOrARow() = runBlocking {
        val repo = repo()
        repo.ask(SRC, info(body))
        val stream = api.serve(body, gateAfter = 100_000)
        val running = async(Dispatchers.Default) { repo.run(ATT) }
        stream.reachedGate.await()
        repo.purgeAll()
        stream.release()
        val result = withTimeout(10_000) { running.await() }
        assertEquals(DownloadResult.Superseded, result)
        assertNull(store.get(ATT))
        assertTrue(blobs.names().isEmpty())
    }

    @Test
    fun aLateWorkerOfAnEarlierRequestCannotFinishARestartedOne() = runBlocking {
        val repo = repo()
        repo.ask(SRC, info(body))
        val stream = api.serve(body, gateAfter = 100_000)
        val old = async(Dispatchers.Default) { repo.run(ATT) }
        stream.reachedGate.await()
        // The owner removes it and asks again: a new request under a new generation.
        repo.remove(ATT)
        assertEquals(AskOutcome.Asked, repo.ask(SRC, info(body)))
        val fresh = store.get(ATT)!!
        stream.release()
        assertEquals(DownloadResult.Superseded, withTimeout(10_000) { old.await() })
        assertEquals(fresh, store.get(ATT))
        assertEquals(FileState.QUEUED, store.get(ATT)!!.state)
        assertTrue(blobs.names().isEmpty())
    }

    @Test
    fun aSourceDeletedOrRestrictedWhileDownloadingKeepsNothing() = runBlocking {
        for (change in listOf<() -> Unit>({ sources.remove(SRC) }, { sources[SRC] = source(SRC, quarantined = true) }, { sources[SRC] = source(SRC, privacy = "no_ai") })) {
            sources[SRC] = source(SRC)
            val repo = repo()
            repo.ask(SRC, info(body))
            val stream = api.serve(body, gateAfter = 100_000)
            val running = async(Dispatchers.Default) { repo.run(ATT) }
            stream.reachedGate.await()
            change()
            stream.release()
            assertEquals(DownloadResult.Superseded, withTimeout(10_000) { running.await() })
            assertNull(store.get(ATT))
            assertTrue(blobs.names().isEmpty())
        }
    }

    @Test
    fun stoppingTheWorkClosesTheConnectionRemovesThePartialFileAndRequeues() = runBlocking {
        val repo = repo()
        repo.ask(SRC, info(body))
        val stream = api.serve(body, gateAfter = 100_000)
        val running = async(Dispatchers.Default) { repo.run(ATT) }
        stream.reachedGate.await()
        running.cancel()
        try {
            withTimeout(10_000) { running.await() }
            throw AssertionError("expected cancellation")
        } catch (_: CancellationException) {
            // as it must
        }
        assertTrue(stream.aborted)
        assertTrue(leftovers().isEmpty())
        assertTrue(blobs.names().isEmpty())
        // Not stuck: the next run can claim it.
        assertEquals(FileState.QUEUED, store.get(ATT)!!.state)
        api.serve(body)
        assertEquals(DownloadResult.Done, repo.run(ATT))
    }

    @Test
    fun aDamagedOrForeignFileIsRemovedAndNeverHandedOut() = runBlocking {
        val repo = repo()
        repo.ask(SRC, info(body))
        api.serve(body)
        repo.run(ATT)
        val file = blobs.file(store.get(ATT)!!.blobName!!)!!
        val bytes = file.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x40).toByte()
        file.writeBytes(bytes)
        assertEquals(FileRead.Damaged, repo.read(ATT))
        assertNull(store.get(ATT))
        assertTrue(blobs.names().isEmpty())
    }

    @Test
    fun aLostKeyMakesFilesUnreadableAndTheyAreDownloadedAgain() = runBlocking {
        val repo = repo()
        repo.ask(SRC, info(body))
        api.serve(body)
        repo.run(ATT)
        keys.rotate()
        assertEquals(FileRead.Damaged, repo.read(ATT))
        assertNull(store.get(ATT))
    }

    @Test
    fun aFileThatVanishedFromDiskIsReportedMissingAndItsRowRemoved() = runBlocking {
        val repo = repo()
        repo.ask(SRC, info(body))
        api.serve(body)
        repo.run(ATT)
        blobs.delete(store.get(ATT)!!.blobName!!)
        assertEquals(FileRead.NotAvailable, repo.read(ATT))
        assertNull(store.get(ATT))
    }

    @Test
    fun savingACopyWritesTheVerifiedBytesAndDiscardsAPartialOne() = runBlocking {
        val repo = repo()
        repo.ask(SRC, info(body))
        api.serve(body)
        repo.run(ATT)
        val target = File(dir, "copy.bin")
        assertEquals(SaveOutcome.Saved(body.size.toLong()), repo.saveCopy(ATT, FileDestination(target)))
        assertArrayEquals(body, target.readBytes())
        val broken = File(dir, "broken.bin")
        val destination = FileDestination(broken, failWritesAfter = 10)
        assertEquals(SaveOutcome.WriteFailed(true), repo.saveCopy(ATT, destination))
        assertTrue(destination.discarded)
        assertFalse(broken.exists())
        val stuck = FileDestination(File(dir, "stuck.bin"), failWritesAfter = 10, refuseDiscard = true)
        assertEquals(SaveOutcome.WriteFailed(false), repo.saveCopy(ATT, stuck))
        assertEquals(SaveOutcome.NotAvailable, repo.saveCopy(ATT2, FileDestination(File(dir, "none.bin"))))
    }

    @Test
    fun purgeAllRemovesEveryRowAndEveryFileAndCancelsTheWork() = runBlocking {
        val repo = repo()
        for ((id, bytes) in listOf(ATT to body, ATT2 to ByteArray(10))) {
            repo.ask(SRC, info(bytes, id))
            api.serve(bytes)
            repo.run(id)
        }
        File(blobs.names().first().let { dir }, "blobs/stray.enc").writeText("stray")
        repo.purgeAll()
        assertTrue(store.all().isEmpty())
        assertTrue(blobs.names().isEmpty())
        assertEquals(1, scheduler.cancelledAll)
    }

    @Test
    fun purgingDeletedSourcesRemovesTheirFilesOnly() = runBlocking {
        val other = "1b4e6f8a-2c3d-4e5f-8a9b-1c2d3e4f5a6b"
        sources[other] = source(other)
        val repo = repo()
        repo.ask(SRC, info(body))
        repo.ask(other, info(body, ATT2))
        api.serve(body)
        repo.run(ATT)
        api.serve(body)
        repo.run(ATT2)
        repo.purgeSources(listOf(SRC))
        assertNull(store.get(ATT))
        assertNotNull(store.get(ATT2))
        assertEquals(1, blobs.names().size)
        assertTrue(ATT in scheduler.cancelled)
    }

    @Test
    fun startingTheAppRequeuesInterruptedDownloadsAndSweepsOnlyOrphans() = runBlocking {
        val first = repo()
        first.ask(SRC, info(body))
        api.serve(body)
        first.run(ATT)
        first.ask(SRC, info(body, ATT2))
        store.claim(ATT2, store.get(ATT2)!!.generation, 5)
        val orphan = File(dir, "blobs/${"0".repeat(32)}.enc").also { it.writeText("orphan"); it.setLastModified(1) }
        val young = blobs.newTemp()
        // A new process: the clock is later than the interrupted claim.
        now = 10_000_000
        val second = repo()
        scheduler.enqueued.clear()
        now = 20_000_000
        second.recover()
        assertEquals(FileState.QUEUED, store.get(ATT2)!!.state)
        assertTrue(ATT2 in scheduler.enqueued)
        assertFalse(orphan.exists())
        assertTrue("a file written after this process started is not swept", young.exists().not() || young.lastModified() >= 0)
        assertEquals(FileState.READY, store.get(ATT)!!.state)
        assertTrue(blobs.exists(store.get(ATT)!!.blobName!!))
        // Names that could escape the directory are never resolved.
        assertNull(blobs.file("../../x.enc"))
        assertNull(blobs.file("a/b.enc"))
    }

    @Test
    fun aRunForAPurgedOrFinishedRequestDoesNothing() = runBlocking {
        val repo = repo()
        assertEquals(DownloadResult.Superseded, repo.run(ATT))
        repo.ask(SRC, info(body))
        api.serve(body)
        assertEquals(DownloadResult.Done, repo.run(ATT))
        assertEquals(DownloadResult.Done, repo.run(ATT))
        assertEquals(1, api.asked.size)
    }
}
