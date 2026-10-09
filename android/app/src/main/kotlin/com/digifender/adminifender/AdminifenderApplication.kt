package com.digifender.adminifender

import android.app.Application
import com.digifender.adminifender.core.data.export.ExportRepository
import com.digifender.adminifender.core.data.files.AttachmentRepository
import com.digifender.adminifender.core.data.platform.FilesHost
import com.digifender.adminifender.core.data.platform.SyncHost
import com.digifender.adminifender.core.data.sync.SyncEngine
import com.digifender.adminifender.push.createChannels

class AdminifenderApplication : Application(), SyncHost, FilesHost {
    val container by lazy { AppContainer(this) }

    override val syncEngine: SyncEngine get() = container.syncEngine

    override val attachments: AttachmentRepository get() = container.attachments

    override val exports: ExportRepository get() = container.exports

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }
}
