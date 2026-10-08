package com.digifender.adminifender.core.data.local

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The phone's private cache (lld.md#sync). Credential-encrypted app storage, excluded from backup and device
 * transfer by the app manifest. Version 1 is the first schema; version 2 adds `source_texts` for opened sources;
 * version 3 adds `briefing`, the last briefing snapshot by reference.
 */
@Database(
    entities = [ConcernEntity::class, SourceEntity::class, PendingMutationEntity::class, SyncStateEntity::class, SourceTextEntity::class, BriefingEntity::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun concerns(): ConcernDao

    abstract fun sources(): SourceDao

    abstract fun mutations(): MutationDao

    abstract fun syncState(): SyncStateDao

    abstract fun sourceTexts(): SourceTextDao

    abstract fun briefing(): BriefingDao

    companion object {
        const val NAME = "adminifender.db"

        fun open(context: Context): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, NAME).build()
    }
}
