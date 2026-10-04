package com.digifender.adminifender

import android.app.Application
import com.digifender.adminifender.core.data.platform.SyncHost
import com.digifender.adminifender.core.data.sync.SyncEngine
import com.digifender.adminifender.push.createChannels

class AdminifenderApplication : Application(), SyncHost {
    val container by lazy { AppContainer(this) }

    override val syncEngine: SyncEngine get() = container.syncEngine

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }
}
