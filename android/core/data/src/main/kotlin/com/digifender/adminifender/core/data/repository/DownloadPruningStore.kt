package com.digifender.adminifender.core.data.repository

import com.digifender.adminifender.core.data.network.SyncChange
import com.digifender.adminifender.core.data.sync.LocalStore

/**
 * Install between SyncEngine and the Room LocalStore. Purge before committing the sync cursor: a failed purge or
 * interrupted sync cannot acknowledge a tombstone while retaining its attachment/export bytes. Rebootstrap
 * conservatively clears all downloads because missing source IDs are themselves deletion evidence.
 */
class DownloadPruningStore(private val delegate: LocalStore, private val downloads: PrivateDownloadStore, private val journal: TransferJournal? = null) : LocalStore by delegate {
    override suspend fun applyChanges(changes: List<SyncChange>, throughSeq: Long, nowMs: Long) {
        val changedSources = changes.mapNotNull { change ->
            when (change) {
                is SyncChange.SourceChanged -> change.source.id
                is SyncChange.Removed -> change.entityId.takeIf { change.entityType == "source" }
                else -> null
            }
        }
        if (changedSources.isNotEmpty()) downloads.purgeSources(changedSources)
        val deletedSources = changes.filterIsInstance<SyncChange.Removed>().filter { it.entityType == "source" }.map { it.entityId }
        if (deletedSources.isNotEmpty()) journal?.invalidateSources(deletedSources)
        delegate.applyChanges(changes, throughSeq, nowMs)
    }

    override suspend fun replaceWithSnapshot(changes: List<SyncChange>, snapshotSeq: Long, nowMs: Long) {
        downloads.clear()
        delegate.replaceWithSnapshot(changes, snapshotSeq, nowMs)
    }
}
