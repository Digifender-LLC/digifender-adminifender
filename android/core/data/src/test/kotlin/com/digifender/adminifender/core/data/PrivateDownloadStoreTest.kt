package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.TransferResult
import com.digifender.adminifender.core.data.repository.PrivateDownloadStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val PRIVATE_SOURCE = "00000000-0000-4000-9000-000000000011"
private const val PRIVATE_ATTACHMENT = "00000000-0000-4000-9000-000000000012"

class PrivateDownloadStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private fun files(root: File) = root.walkTopDown().filter { it.isFile }.toList()

    @Test
    fun successfulBytesPublishOnlyUnderOpaqueIdsAndRemovalDeletesThem() = runTest {
        val root = folder.newFolder()
        val store = PrivateDownloadStore(root)
        val result = store.attachment(PRIVATE_SOURCE, PRIVATE_ATTACHMENT) { it.write("mail".toByteArray()); TransferResult.Ok(4) }
        assertTrue(result is TransferResult.Ok)
        assertEquals(listOf("attachments/$PRIVATE_SOURCE/$PRIVATE_ATTACHMENT"), files(root).map { it.relativeTo(root).path })
        store.removeAttachment(PRIVATE_SOURCE, PRIVATE_ATTACHMENT)
        assertTrue(files(root).isEmpty())
    }

    @Test
    fun signOutDuringSuspendedDownloadCannotRepublishBytes() = runTest {
        val root = folder.newFolder()
        val store = PrivateDownloadStore(root)
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val work = async {
            store.attachment(PRIVATE_SOURCE, PRIVATE_ATTACHMENT) { output ->
                output.write("mail".toByteArray())
                started.complete(Unit)
                resume.await()
                TransferResult.Ok(4)
            }
        }
        started.await()
        store.clear()
        resume.complete(Unit)
        assertTrue(work.await() is TransferResult.Rejected)
        assertTrue(files(root).isEmpty())
    }

    @Test
    fun tombstoneInvalidatesInFlightFileAndExistingExports() = runTest {
        val root = folder.newFolder()
        val store = PrivateDownloadStore(root)
        store.export(PRIVATE_ATTACHMENT, { it.write(1); TransferResult.Ok(1) }, { true })
        val result = store.attachment(PRIVATE_SOURCE, PRIVATE_ATTACHMENT) { output ->
            output.write(1)
            store.purgeSources(listOf(PRIVATE_SOURCE))
            TransferResult.Ok(1)
        }
        assertTrue(result is TransferResult.Rejected)
        assertTrue(files(root).isEmpty())
    }

    @Test
    fun interruptedCorruptAndRejectedDownloadsLeaveNoSavedOrPartialBytes() = runTest {
        val root = folder.newFolder()
        val store = PrivateDownloadStore(root)
        assertTrue(store.attachment(PRIVATE_SOURCE, PRIVATE_ATTACHMENT) { it.write(1); TransferResult.Unavailable("network") } is TransferResult.Unavailable)
        assertTrue(store.attachment(PRIVATE_SOURCE, PRIVATE_ATTACHMENT) { it.write(1); TransferResult.Ok(2) } is TransferResult.Rejected)
        assertTrue(store.export(PRIVATE_ATTACHMENT, { it.write(1); TransferResult.Ok(1) }, { false }) is TransferResult.Rejected)
        assertTrue(files(root).isEmpty())
    }

    @Test
    fun failedPurgeThrowsSoSyncCannotAcknowledgeItsCursor() = runTest {
        val root = folder.newFolder()
        val store = PrivateDownloadStore(root) { false }
        store.attachment(PRIVATE_SOURCE, PRIVATE_ATTACHMENT) { it.write(1); TransferResult.Ok(1) }
        try {
            store.purgeSources(listOf(PRIVATE_SOURCE))
            fail("Purge must not silently succeed")
        } catch (_: IOException) {
            assertEquals(1, files(root).size)
        }
    }

    @Test
    fun attachmentCacheEvictsTheLeastRecentlyUsedCopyWithinItsByteLimit() = runTest {
        val root = folder.newFolder()
        val store = PrivateDownloadStore(root, maxAttachmentCacheBytes = 2)
        val second = "00000000-0000-4000-9000-000000000013"
        val third = "00000000-0000-4000-9000-000000000014"
        store.attachment(PRIVATE_SOURCE, PRIVATE_ATTACHMENT) { it.write(1); TransferResult.Ok(1L) }
        File(root, "attachments/$PRIVATE_SOURCE/$PRIVATE_ATTACHMENT").setLastModified(100)
        store.attachment(PRIVATE_SOURCE, second) { it.write(2); TransferResult.Ok(1L) }
        File(root, "attachments/$PRIVATE_SOURCE/$second").setLastModified(200)
        store.attachment(PRIVATE_SOURCE, third) { it.write(3); TransferResult.Ok(1L) }
        assertEquals(setOf(second, third), store.savedAttachments(PRIVATE_SOURCE))
        assertEquals(2L, files(root).sumOf { it.length() })
    }

    @Test
    fun closedStorageRejectsNewAndPreviouslyPreparedDownloadsUntilFreshSessionOpensIt() = runTest {
        val root = folder.newFolder()
        val store = PrivateDownloadStore(root)
        val oldFence = store.fence
        store.close()
        assertEquals(TransferResult.Rejected("download_invalidated"), store.attachment(PRIVATE_SOURCE, PRIVATE_ATTACHMENT) { error("closed store cannot fetch") })
        store.open()
        assertEquals(TransferResult.Rejected("download_invalidated"), store.attachment(PRIVATE_SOURCE, PRIVATE_ATTACHMENT, oldFence) { error("old session cannot fetch") })
        assertTrue(store.attachment(PRIVATE_SOURCE, PRIVATE_ATTACHMENT) { it.write(1); TransferResult.Ok(1L) } is TransferResult.Ok)
        val version = store.changes.value
        store.purgeSources(listOf(PRIVATE_SOURCE))
        assertTrue(store.changes.value > version)
        assertTrue(store.savedAttachments(PRIVATE_SOURCE).isEmpty())
    }

    @Test
    fun processRestartRemovesPartialFilesAndTraversalIsRejected() = runTest {
        val root = folder.newFolder()
        File(root, "old.part").writeText("synthetic partial")
        val store = PrivateDownloadStore(root)
        assertTrue(files(root).isEmpty())
        assertEquals(TransferResult.Rejected("invalid_request"), store.attachment("../outside", PRIVATE_ATTACHMENT) { error("must not download") })
    }
}
