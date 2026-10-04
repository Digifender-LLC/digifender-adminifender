package com.digifender.adminifender

import android.app.Application
import com.digifender.adminifender.core.data.platform.SyncHost
import com.digifender.adminifender.core.data.sync.SyncEngine

class AdminifenderApplication : Application(), SyncHost {
    val container by lazy { AppContainer(this) }

    override val syncEngine: SyncEngine get() = container.syncEngine
}
