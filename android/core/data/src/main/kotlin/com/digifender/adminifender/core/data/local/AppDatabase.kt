package com.digifender.adminifender.core.data.local

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The phone's private cache (lld.md#sync). Credential-encrypted app storage, excluded from backup and device
 * transfer by the app manifest. Version 1 is the first schema; version 2 adds `source_texts` for opened sources;
 * version 3 adds `briefing`, the last briefing snapshot by reference; version 4 adds `chat_threads` and `chat_turns`
 * and the nullable `concerns.serviceId` (filled by the next full download, which the migration requests).
 */
@Database(
    entities = [ConcernEntity::class, SourceEntity::class, PendingMutationEntity::class, SyncStateEntity::class, SourceTextEntity::class, BriefingEntity::class, ChatThreadEntity::class, ChatTurnEntity::class],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4, spec = RequestFullDownload::class)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun concerns(): ConcernDao

    abstract fun sources(): SourceDao

    abstract fun mutations(): MutationDao

    abstract fun syncState(): SyncStateDao

    abstract fun sourceTexts(): SourceTextDao

    abstract fun briefing(): BriefingDao

    abstract fun chat(): ChatDao

    companion object {
        const val NAME = "adminifender.db"

        fun open(context: Context): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, NAME).build()
    }
}

/**
 * Version 4 adds `concerns.serviceId`, which only a fresh server projection fills. Asking for a full download keeps
 * queued edits (they are resolved by sending them) and lets chat offer a service scope for concerns that already
 * had one, instead of silently hiding it until each concern next changes.
 */
class RequestFullDownload : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE sync_state SET needsBootstrap = 1")
    }
}
