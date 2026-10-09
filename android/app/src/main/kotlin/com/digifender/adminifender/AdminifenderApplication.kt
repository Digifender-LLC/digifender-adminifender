package com.digifender.adminifender

import android.app.Application
import com.digifender.adminifender.core.data.device.DeviceWorkRunner
import com.digifender.adminifender.core.data.export.ExportRepository
import com.digifender.adminifender.core.data.extraction.DeviceExtractor
import com.digifender.adminifender.core.data.files.AttachmentRepository
import com.digifender.adminifender.core.data.platform.DeviceWorkHost
import com.digifender.adminifender.core.data.platform.ExtractionHost
import com.digifender.adminifender.core.data.platform.FilesHost
import com.digifender.adminifender.core.data.platform.SyncHost
import com.digifender.adminifender.core.data.sync.SyncEngine
import com.digifender.adminifender.push.createChannels

class AdminifenderApplication : Application(), SyncHost, FilesHost, ExtractionHost, DeviceWorkHost {
    val container by lazy { AppContainer(this) }

    override val syncEngine: SyncEngine get() = container.syncEngine

    override val attachments: AttachmentRepository get() = container.attachments

    override val exports: ExportRepository get() = container.exports

    override val extractor: DeviceExtractor get() = container.extractor

    override val deviceWork: DeviceWorkRunner get() = container.deviceWork

    override fun onCreate() {
        super.onCreate()
        // Foreground state for the interactive local-inference gate.
        registerActivityLifecycleCallbacks(container.probe)
        createChannels(this)
    }
}
