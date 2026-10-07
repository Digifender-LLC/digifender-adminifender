package com.digifender.adminifender

import android.content.Context
import android.os.Build
import com.digifender.adminifender.core.data.chat.ChatRepository
import com.digifender.adminifender.core.data.chat.HttpChatApi
import com.digifender.adminifender.core.data.chat.openPrivateChatStore
import com.digifender.adminifender.core.data.devicework.DeviceWorkEngine
import com.digifender.adminifender.core.data.devicework.HttpDeviceWorkApi
import com.digifender.adminifender.core.data.devicework.UnavailableLocalInferenceRuntime
import com.digifender.adminifender.core.data.local.LocalCache
import com.digifender.adminifender.core.data.network.HttpApi
import com.digifender.adminifender.core.data.network.HttpArchiveTransferApi
import com.digifender.adminifender.core.data.network.HttpBriefingApi
import com.digifender.adminifender.core.data.platform.AndroidDeviceConditions
import com.digifender.adminifender.core.data.platform.KeystoreTokenVault
import com.digifender.adminifender.core.data.platform.WorkSyncScheduler
import com.digifender.adminifender.core.data.platform.WorkDeviceScheduler
import com.digifender.adminifender.core.data.push.HttpPushApi
import com.digifender.adminifender.push.PushRegistrationWorker
import com.digifender.adminifender.core.data.repository.ArchiveRepository
import com.digifender.adminifender.core.data.repository.ConcernRepository
import com.digifender.adminifender.core.data.repository.AttachmentRepository
import com.digifender.adminifender.core.data.repository.BriefingRepository
import com.digifender.adminifender.core.data.repository.DownloadPruningStore
import com.digifender.adminifender.core.data.repository.ExportRepository
import com.digifender.adminifender.core.data.repository.FileTransferJournal
import com.digifender.adminifender.core.data.repository.PrivateDownloadStore
import com.digifender.adminifender.core.data.repository.ReviewRepository
import com.digifender.adminifender.core.data.session.HttpAuthApi
import com.digifender.adminifender.core.data.session.SessionManager
import com.digifender.adminifender.core.data.sync.SyncEngine
import java.util.concurrent.TimeUnit
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** Constructor injection by hand: one instance of each adapter for the process (lld.md#android). */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val clock: () -> Long = System::currentTimeMillis
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val baseUrl = BuildConfig.API_BASE_URL.toHttpUrl()
    private val sessionGeneration = AtomicLong(0)
    private val mutableSigningOut = MutableStateFlow(false)
    val signingOut = mutableSigningOut.asStateFlow()
    private val cleanupState = app.getSharedPreferences("private-cleanup", Context.MODE_PRIVATE)
    fun cleanupPending(): Boolean = cleanupState.getBoolean("pending", false)

    val session = SessionManager(HttpAuthApi(http, baseUrl), KeystoreTokenVault(app), clock, deviceName = Build.MODEL.take(80).ifBlank { "Android device" })
    private val cache = LocalCache(app)
    val scheduler = WorkSyncScheduler(app)
    private val api = HttpApi(http, baseUrl, session)
    private val transfers = HttpArchiveTransferApi(http, baseUrl, session)
    private val downloads = PrivateDownloadStore(File(app.noBackupFilesDir, "archive-downloads"))
    private val transferJournal = FileTransferJournal(File(app.noBackupFilesDir, "archive-transfers"))
    val syncEngine = SyncEngine(api, DownloadPruningStore(cache.store, downloads, journal = transferJournal), clock, sessionGeneration::get) { !mutableSigningOut.value }
    val concerns = ConcernRepository(cache.source, cache.store, scheduler, clock)
    val archive = ArchiveRepository(api, cache.archive, cache.source, clock)
    val briefings = BriefingRepository(HttpBriefingApi(http, baseUrl, session), cache.source, scheduler)
    val chat = ChatRepository(HttpChatApi(http, baseUrl, session), openPrivateChatStore(app))
    val attachments = AttachmentRepository(api, transfers, downloads)
    val reviews = ReviewRepository(transfers, clock, onDeleted = { ids, concerns ->
        try { downloads.purgeSources(ids) } finally {
            cache.invalidateSources(ids, concerns)
            scheduler.requestNow()
        }
    }, journal = transferJournal)
    val exports = ExportRepository(transfers, transfers, downloads, clock, journal = transferJournal)
    val deviceConditions = AndroidDeviceConditions(app)
    val deviceWorkEngine = DeviceWorkEngine(HttpDeviceWorkApi(http, baseUrl, session), deviceConditions, UnavailableLocalInferenceRuntime(), clock)
    private val deviceScheduler = WorkDeviceScheduler(app)
    val push = HttpPushApi(http, baseUrl, session)

    /** Starts background sync for a signed-in owner. */
    fun startSync() {
        downloads.open()
        transferJournal.open()
        scheduler.schedulePeriodic()
        scheduler.requestNow()
        PushRegistrationWorker.enqueue(app)
        deviceScheduler.request()
    }

    /** Sign-out cancels sync work and clears private cached state (lld.md#sync). Queued edits are discarded with it. */
    suspend fun signOut() = withContext(NonCancellable) {
        if (mutableSigningOut.value) return@withContext
        mutableSigningOut.value = true
        sessionGeneration.incrementAndGet()
        downloads.close()
        transferJournal.close()
        scheduler.cancelAll()
        deviceScheduler.cancel()
        PushRegistrationWorker.cancel(app)
        http.dispatcher.cancelAll()
        try {
            // A restart must resume incomplete local cleanup even after credentials have already been removed.
            withContext(Dispatchers.IO) { cleanupState.edit().putBoolean("pending", true).commit() }
            // Wait through any suspended store commit before clearing; new sync passes are already blocked.
            syncEngine.awaitIdle()
            // Fence private file writes first, before any logout HTTP request can stall cleanup.
            try { transferJournal.clear() } finally {
                try { downloads.clear() } finally {
                    try { chat.clear() } finally { cache.clear() }
                }
            }
        } finally {
            try { session.signOut() } finally {
                // Publication stays closed through logout and this last purge; only a new sign-in reopens it.
                try { transferJournal.clear() } finally {
                    try { downloads.clear() } finally { mutableSigningOut.value = false }
                }
            }
        }
        if (!withContext(Dispatchers.IO) { cleanupState.edit().remove("pending").commit() }) throw IOException("cleanup_state_unavailable")
    }
}
