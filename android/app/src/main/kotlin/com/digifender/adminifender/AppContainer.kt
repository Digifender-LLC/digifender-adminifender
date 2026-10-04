package com.digifender.adminifender

import android.content.Context
import android.os.Build
import android.util.Base64
import com.digifender.adminifender.core.data.device.DeviceWorkRunner
import com.digifender.adminifender.core.data.extraction.ExtractionRunner
import com.digifender.adminifender.core.data.platform.DeviceWorkWorker
import com.digifender.adminifender.core.data.platform.ExtractionWorker
import com.digifender.adminifender.core.data.repository.ChatRepository
import com.digifender.adminifender.core.model.WorkKind
import com.digifender.adminifender.extraction.openDocument
import com.digifender.adminifender.inference.DeviceProbe
import com.digifender.adminifender.inference.ModelState
import com.digifender.adminifender.inference.ModelStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.digifender.adminifender.core.data.local.LocalCache
import com.digifender.adminifender.core.data.network.HttpApi
import com.digifender.adminifender.core.data.platform.KeystoreTokenVault
import com.digifender.adminifender.core.data.platform.WorkSyncScheduler
import com.digifender.adminifender.core.data.push.HttpPushApi
import com.digifender.adminifender.push.PushRegistrationWorker
import com.digifender.adminifender.core.data.repository.ArchiveRepository
import com.digifender.adminifender.core.data.repository.ConcernRepository
import com.digifender.adminifender.core.data.session.HttpAuthApi
import com.digifender.adminifender.core.data.session.SessionManager
import com.digifender.adminifender.core.data.sync.SyncEngine
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** Constructor injection by hand: one instance of each adapter for the process (lld.md#android). */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val clock: () -> Long = System::currentTimeMillis
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val baseUrl = BuildConfig.API_BASE_URL.toHttpUrl()

    val session = SessionManager(HttpAuthApi(http, baseUrl), KeystoreTokenVault(app), clock, deviceName = Build.MODEL.take(80).ifBlank { "Android device" })
    private val cache = LocalCache(app)
    val scheduler = WorkSyncScheduler(app)
    private val api = HttpApi(http, baseUrl, session)
    val syncEngine = SyncEngine(api, cache.store, clock)
    val concerns = ConcernRepository(cache.source, cache.store, scheduler, clock)
    val archive = ArchiveRepository(api, cache.archive, cache.source, clock)
    val push = HttpPushApi(http, baseUrl, session)
    val chat = ChatRepository(cache.chat, api, clock)

    val probe = DeviceProbe(app)
    val models = ModelStore(app, BuildConfig.MODEL_MANIFEST_B64.takeIf { it.isNotEmpty() }?.let { String(Base64.decode(it, Base64.DEFAULT)) })
    val deviceWork = DeviceWorkRunner(api, models::model, probe::readiness)
    private val extractionDir = File(app.noBackupFilesDir, "extraction")
    val extraction = ExtractionRunner(api, { probe.readiness(null) }, ::openDocument, extractionDir, { UUID.randomUUID().toString() })
    private val foregroundWork = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Starts background sync for a signed-in owner. */
    fun startSync() {
        scheduler.schedulePeriodic()
        scheduler.requestNow()
        PushRegistrationWorker.enqueue(app)
        ExtractionWorker.schedule(app)
        if (models.state.value is ModelState.Installed) DeviceWorkWorker.schedule(app)
    }

    /**
     * Offers the phone for interactive work while the app is open, for a chat turn waiting on local-only evidence.
     * Whatever happens shows up through the turn's server state, not through this call.
     */
    fun runLocalChatNow() {
        if (models.state.value !is ModelState.Installed) return
        foregroundWork.launch { deviceWork.runOnce(WorkKind.INTERACTIVE) }
    }

    /** The owner's "Resume next pages"; the phone picks the work up on its next charging run. */
    suspend fun resumeExtraction(attachmentId: String) = api.resumeExtraction(attachmentId, UUID.randomUUID().toString())

    /** Sign-out cancels sync work and clears private cached state (lld.md#sync). Queued edits are discarded with it. */
    suspend fun signOut() {
        scheduler.cancelAll()
        PushRegistrationWorker.cancel(app)
        ExtractionWorker.cancel(app)
        DeviceWorkWorker.cancel(app)
        session.signOut()
        cache.clear()
        // Downloaded attachments are private cache and go with the session (lld.md#text-extraction-and-ocr).
        withContext(Dispatchers.IO) { extractionDir.deleteRecursively() }
    }
}
