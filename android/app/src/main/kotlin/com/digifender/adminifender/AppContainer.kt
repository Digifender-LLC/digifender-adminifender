package com.digifender.adminifender

import android.content.Context
import android.annotation.SuppressLint
import android.os.Build
import com.digifender.adminifender.core.data.chat.ChatRepository
import com.digifender.adminifender.core.data.chat.HttpChatApi
import com.digifender.adminifender.core.data.chat.openPrivateChatStore
import com.digifender.adminifender.core.data.devicework.DeviceWorkEngine
import com.digifender.adminifender.core.data.devicework.HttpDeviceWorkApi
import com.digifender.adminifender.core.data.devicework.UnavailableLocalInferenceRuntime
import com.digifender.adminifender.core.data.health.HealthRepository
import com.digifender.adminifender.core.data.health.HttpHealthApi
import com.digifender.adminifender.core.data.local.LocalCache
import com.digifender.adminifender.core.data.network.HttpApi
import com.digifender.adminifender.core.data.network.HttpArchiveTransferApi
import com.digifender.adminifender.core.data.network.HttpBriefingApi
import com.digifender.adminifender.core.data.platform.AndroidDeviceConditions
import com.digifender.adminifender.core.data.platform.ExportDocumentWriter
import com.digifender.adminifender.core.data.platform.KeystoreTokenVault
import com.digifender.adminifender.core.data.platform.WorkSyncScheduler
import com.digifender.adminifender.core.data.platform.WorkDeviceScheduler
import com.digifender.adminifender.core.data.push.HttpPushApi
import com.digifender.adminifender.push.PushRegistrationWorker
import com.digifender.adminifender.ui.AndroidHealthDeviceProbe
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
    @Volatile private var needsCleanup = cleanupState.getBoolean("pending", false)
    fun cleanupPending(): Boolean = needsCleanup
    fun privateSessionEpoch(): Long = sessionGeneration.get()

    val session = SessionManager(HttpAuthApi(http, baseUrl), KeystoreTokenVault(app), clock, deviceName = Build.MODEL.take(80).ifBlank { "Android device" })
    private val access = CleanupAccessTokens(session) { needsCleanup || mutableSigningOut.value }
    private val cache = LocalCache(app)
    val scheduler = WorkSyncScheduler(app)
    private val api = HttpApi(http, baseUrl, access)
    private val transfers = HttpArchiveTransferApi(http, baseUrl, access)
    private val downloads = PrivateDownloadStore(File(app.noBackupFilesDir, "archive-downloads"))
    private val transferJournal = FileTransferJournal(File(app.noBackupFilesDir, "archive-transfers"))
    private var storesClosed = needsCleanup
    init { if (storesClosed) { downloads.close(); transferJournal.close() } }
    val syncEngine = SyncEngine(api, DownloadPruningStore(cache.store, downloads, journal = transferJournal), clock, sessionGeneration::get) { !mutableSigningOut.value && !needsCleanup }
    val concerns = ConcernRepository(cache.source, cache.store, scheduler, clock)
    val archive = ArchiveRepository(api, cache.archive, cache.source, clock)
    val briefings = BriefingRepository(HttpBriefingApi(http, baseUrl, access), cache.source, scheduler)
    val chat = ChatRepository(HttpChatApi(http, baseUrl, access), openPrivateChatStore(app))
    val attachments = AttachmentRepository(api, transfers, downloads)
    val reviews = ReviewRepository(transfers, clock, onDeleted = { ids, concerns ->
        try { downloads.purgeSources(ids) } finally {
            cache.invalidateSources(ids, concerns)
            scheduler.requestNow()
        }
    }, journal = transferJournal)
    val exports = ExportRepository(transfers, transfers, downloads, clock, journal = transferJournal)
    val exportDocuments = ExportDocumentWriter(app.contentResolver, exports)
    val deviceConditions = AndroidDeviceConditions(app)
    private val localRuntime = UnavailableLocalInferenceRuntime()
    val deviceWorkEngine = DeviceWorkEngine(HttpDeviceWorkApi(http, baseUrl, access), deviceConditions, localRuntime, clock)
    val health = HealthRepository(HttpHealthApi(http, baseUrl, access))
    val healthProbe = AndroidHealthDeviceProbe(app, deviceConditions, localRuntime::status) { deviceWorkEngine.state.value }
    private val deviceScheduler = WorkDeviceScheduler(app)
    val push = HttpPushApi(http, baseUrl, access)

    /** Starts background sync for a signed-in owner. */
    fun startSync() {
        check(!needsCleanup && !mutableSigningOut.value) { "private_cleanup_pending" }
        // Ordinary passkey reauthentication retains durable request identities and their publication epoch.
        if (storesClosed) {
            downloads.open()
            transferJournal.open()
            storesClosed = false
        }
        scheduler.schedulePeriodic()
        scheduler.requestNow()
        PushRegistrationWorker.enqueue(app)
        deviceScheduler.request()
    }

    /** Sign-out cancels sync work and clears private cached state (lld.md#sync). Queued edits are discarded with it. */
    suspend fun signOut() = withContext(NonCancellable) {
        if (mutableSigningOut.value) return@withContext
        mutableSigningOut.value = true
        needsCleanup = true
        sessionGeneration.incrementAndGet()
        storesClosed = true
        downloads.close()
        transferJournal.close()
        scheduler.cancelAll()
        deviceScheduler.cancel()
        PushRegistrationWorker.cancel(app)
        http.dispatcher.cancelAll()
        var markerWritten = false
        try {
            // A restart must resume incomplete local cleanup even after credentials have already been removed.
            markerWritten = runCatching { persistCleanupMarker(true) }.getOrDefault(false)
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
        if (!markerWritten) throw IOException("cleanup_state_unavailable")
        if (!persistCleanupMarker(false)) throw IOException("cleanup_state_unavailable")
        needsCleanup = false
    }

    // KTX edit returns Unit; this safety boundary must inspect the synchronous commit result.
    @SuppressLint("UseKtx")
    private suspend fun persistCleanupMarker(pending: Boolean): Boolean = withContext(Dispatchers.IO) {
        val edit = cleanupState.edit()
        if (pending) edit.putBoolean("pending", true) else edit.remove("pending")
        edit.commit()
    }
}
