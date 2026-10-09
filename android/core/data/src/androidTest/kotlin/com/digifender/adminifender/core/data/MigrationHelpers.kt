package com.digifender.adminifender.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.digifender.adminifender.core.data.local.AppDatabase
import com.digifender.adminifender.core.data.local.RequestFullDownload

/** Room checks every auto-migration spec of the database when it migrates, so the helper is given the one version 4 needs. */
internal fun migrationHelper() = MigrationTestHelper(
    InstrumentationRegistry.getInstrumentation(),
    AppDatabase::class.java,
    listOf(RequestFullDownload()),
    FrameworkSQLiteOpenHelperFactory(),
)
