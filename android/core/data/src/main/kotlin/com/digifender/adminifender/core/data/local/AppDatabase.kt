package com.digifender.adminifender.core.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The phone's private cache (lld.md#sync). Credential-encrypted app storage, excluded from backup and device
 * transfer by the app manifest. Version 1 is the first schema; later versions add tested migrations.
 */
@Database(entities = [ConcernEntity::class, SourceEntity::class, PendingMutationEntity::class, SyncStateEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun concerns(): ConcernDao

    abstract fun sources(): SourceDao

    abstract fun mutations(): MutationDao

    abstract fun syncState(): SyncStateDao

    companion object {
        const val NAME = "adminifender.db"

        fun open(context: Context): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, NAME).build()
    }
}
