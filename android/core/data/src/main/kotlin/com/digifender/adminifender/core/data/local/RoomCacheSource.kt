package com.digifender.adminifender.core.data.local

import com.digifender.adminifender.core.data.repository.CacheSource
import kotlinx.coroutines.flow.map

/** Room-backed [CacheSource]; every flow re-emits after the transaction that changed its tables commits. */
class RoomCacheSource(private val db: AppDatabase) : CacheSource {
    override fun concerns() = db.concerns().observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun mutations() = db.mutations().observeAll().map { rows -> rows.map { it.toQueued() } }

    override fun syncState() = db.syncState().observe().map { row -> (row?.lastSyncedAt) to (row?.needsBootstrap ?: true) }
}
