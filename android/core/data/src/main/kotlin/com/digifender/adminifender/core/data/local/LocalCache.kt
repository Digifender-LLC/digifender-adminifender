package com.digifender.adminifender.core.data.local

import android.content.Context
import com.digifender.adminifender.core.data.export.ExportStore
import com.digifender.adminifender.core.data.files.BlobDirectory
import com.digifender.adminifender.core.data.files.LocalFileStore
import com.digifender.adminifender.core.data.repository.ArchiveCache
import com.digifender.adminifender.core.data.review.DeletedSourcePurge
import com.digifender.adminifender.core.data.review.DeletionStore
import java.io.File
import com.digifender.adminifender.core.data.repository.BriefingCache
import com.digifender.adminifender.core.data.repository.ChatCache
import com.digifender.adminifender.core.data.repository.CacheSource
import com.digifender.adminifender.core.data.sync.LocalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The app's private cache: the Room database with its read flows and transactional store. */
class LocalCache(context: Context, filesDropped: FilesDropped? = null) {
    private val db = AppDatabase.open(context.applicationContext)

    /** Sealed attachment files. `noBackupFilesDir` is credential-encrypted app storage that is never backed up or transferred. */
    val blobs = BlobDirectory(File(context.applicationContext.noBackupFilesDir, "attachments"))
    val store: LocalStore = RoomLocalStore(db, blobs, filesDropped)
    val source: CacheSource = RoomCacheSource(db)
    val archive: ArchiveCache = RoomArchiveCache(db)
    val briefing: BriefingCache = RoomBriefingCache(db)
    private val roomChat = RoomChatCache(db)
    val chat: ChatCache = roomChat
    val files: LocalFileStore = RoomFileStore(db)
    val deletions: DeletionStore = RoomDeletionStore(db)
    val exports: ExportStore = RoomExportStore(db)
    val purge: DeletedSourcePurge = RoomDeletedSourcePurge(db, roomChat, blobs)

    /** Removes every cached projection and queued edit, for sign-out (lld.md#sync). */
    suspend fun clear() = withContext(Dispatchers.IO) { db.clearAllTables() }
}
