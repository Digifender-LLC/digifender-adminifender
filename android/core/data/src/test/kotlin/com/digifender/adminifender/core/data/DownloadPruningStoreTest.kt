package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.SyncChange
import com.digifender.adminifender.core.data.network.TransferResult
import com.digifender.adminifender.core.data.repository.DownloadPruningStore
import com.digifender.adminifender.core.data.repository.PrivateDownloadStore
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val PRUNE_SOURCE = "00000000-0000-4000-9000-000000000061"
private const val PRUNE_ATTACHMENT = "00000000-0000-4000-9000-000000000062"

class DownloadPruningStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun tombstoneRemovesBytesBeforeCursorCanAdvance() = runTest {
        val files = PrivateDownloadStore(folder.root)
        files.attachment(PRUNE_SOURCE, PRUNE_ATTACHMENT) { it.write(1); TransferResult.Ok(1L) }
        val store = InMemoryStore()
        DownloadPruningStore(store, files).applyChanges(listOf(SyncChange.Removed(9, "source", PRUNE_SOURCE)), 9, 100)
        assertEquals(9L, store.state.throughSeq)
        assertFalse(folder.root.walkTopDown().any { it.isFile })
    }

    @Test
    fun failedPurgeDoesNotCommitSyncCursor() = runTest {
        val files = PrivateDownloadStore(folder.root) { false }
        files.attachment(PRUNE_SOURCE, PRUNE_ATTACHMENT) { it.write(1); TransferResult.Ok(1L) }
        val store = InMemoryStore()
        try {
            DownloadPruningStore(store, files).applyChanges(listOf(SyncChange.Removed(9, "source", PRUNE_SOURCE)), 9, 100)
            fail("Purge failure must stop the sync transaction")
        } catch (_: IOException) { assertEquals(0L, store.state.throughSeq) }
    }

    @Test
    fun completeRebootstrapPurgesFilesForSourcesMissingFromSnapshot() = runTest {
        val files = PrivateDownloadStore(folder.root)
        files.attachment(PRUNE_SOURCE, PRUNE_ATTACHMENT) { it.write(1); TransferResult.Ok(1L) }
        val store = InMemoryStore()
        DownloadPruningStore(store, files).replaceWithSnapshot(emptyList(), 20, 100)
        assertEquals(20L, store.state.throughSeq)
        assertFalse(folder.root.walkTopDown().any { it.isFile })
    }
}
