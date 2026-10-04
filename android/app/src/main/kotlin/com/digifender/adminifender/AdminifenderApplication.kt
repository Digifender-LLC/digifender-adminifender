package com.digifender.adminifender

import android.app.Application
import com.digifender.adminifender.core.data.device.DeviceWorkRunner
import com.digifender.adminifender.core.data.extraction.ExtractionRunner
import com.digifender.adminifender.core.data.platform.DeviceWorkHost
import com.digifender.adminifender.core.data.platform.ExtractionHost
import com.digifender.adminifender.core.data.platform.SyncHost
import com.digifender.adminifender.core.data.sync.SyncEngine
import com.digifender.adminifender.push.createChannels

class AdminifenderApplication : Application(), SyncHost, DeviceWorkHost, ExtractionHost {
    val container by lazy { AppContainer(this) }

    override val syncEngine: SyncEngine get() = container.syncEngine

    override val deviceWork: DeviceWorkRunner get() = container.deviceWork

    override val extraction: ExtractionRunner get() = container.extraction

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(container.probe)
        createChannels(this)
    }
}
