package com.digifender.adminifender.core.data.local

import android.content.Context
import com.digifender.adminifender.core.data.repository.ArchiveCache
import com.digifender.adminifender.core.data.repository.BriefingCache
import com.digifender.adminifender.core.data.repository.ChatCache
import com.digifender.adminifender.core.data.repository.CacheSource
import com.digifender.adminifender.core.data.sync.LocalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The app's private cache: the Room database with its read flows and transactional store. */
class LocalCache(context: Context) {
    private val db = AppDatabase.open(context.applicationContext)
    val store: LocalStore = RoomLocalStore(db)
    val source: CacheSource = RoomCacheSource(db)
    val archive: ArchiveCache = RoomArchiveCache(db)
    val briefing: BriefingCache = RoomBriefingCache(db)
    val chat: ChatCache = RoomChatCache(db)

    /** Removes every cached projection and queued edit, for sign-out (lld.md#sync). */
    suspend fun clear() = withContext(Dispatchers.IO) { db.clearAllTables() }
}
