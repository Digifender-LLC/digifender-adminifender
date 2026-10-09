package com.digifender.adminifender

import android.content.Context
import android.os.Build
import com.digifender.adminifender.core.data.device.DeviceWorkRunner
import com.digifender.adminifender.core.data.export.ExportRepository
import com.digifender.adminifender.core.data.extraction.DeviceExtractor
import com.digifender.adminifender.core.data.extraction.ExtractionCapabilities
import com.digifender.adminifender.core.data.files.AttachmentRepository
import com.digifender.adminifender.core.data.files.FileCipher
import com.digifender.adminifender.core.data.files.SessionEnd
import com.digifender.adminifender.core.data.local.FilesDropped
import com.digifender.adminifender.core.data.local.LocalCache
import com.digifender.adminifender.core.data.network.HttpApi
import com.digifender.adminifender.core.data.network.HttpDeviceWorkApi
import com.digifender.adminifender.core.data.network.HttpExportApi
import com.digifender.adminifender.core.data.network.HttpExtractionApi
import com.digifender.adminifender.core.data.network.HttpReviewApi
import com.digifender.adminifender.core.data.platform.AndroidRunConditions
import com.digifender.adminifender.core.data.platform.DeviceWorkWorker
import com.digifender.adminifender.core.data.platform.KeystoreFileKeys
import com.digifender.adminifender.core.data.platform.MlKitTextRecognizer
import com.digifender.adminifender.core.data.platform.PDF_TEXT_LAYER_AVAILABLE
import com.digifender.adminifender.core.data.platform.PlatformDocumentOpener
import com.digifender.adminifender.core.data.platform.KeystoreTokenVault
import com.digifender.adminifender.core.data.platform.WorkDownloadScheduler
import com.digifender.adminifender.core.data.platform.WorkExportScheduler
import com.digifender.adminifender.core.data.platform.WorkExtractionScheduler
import com.digifender.adminifender.core.data.platform.WorkSyncScheduler
import com.digifender.adminifender.core.data.push.HttpPushApi
import com.digifender.adminifender.push.PushRegistrationWorker
import com.digifender.adminifender.core.data.repository.ArchiveRepository
import com.digifender.adminifender.core.data.repository.BriefingRepository
import com.digifender.adminifender.core.data.repository.ChatRepository
import com.digifender.adminifender.core.data.repository.ConcernRepository
import com.digifender.adminifender.core.data.review.DeletedSourcePurge
import com.digifender.adminifender.core.data.review.DeletionRepository
import com.digifender.adminifender.core.data.review.RecentAuthenticator
import com.digifender.adminifender.core.data.session.HttpAuthApi
import com.digifender.adminifender.core.data.session.SessionManager
import com.digifender.adminifender.core.data.sync.SyncEngine
import com.digifender.adminifender.core.model.WorkKind
import com.digifender.adminifender.inference.DeviceProbe
import com.digifender.adminifender.inference.ModelState
import com.digifender.adminifender.inference.ModelStore
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** How long sign-out waits, in all, for the best-effort cancel requests; it never blocks longer than this. */
private const val CANCEL_ON_SIGN_OUT_MS = 5_000L

/** Identifies this build's way of reading documents to the Worker; part of a generation's identity, so a new reader never mixes with an old one. */
private const val EXTRACTOR_VERSION = "android-extract-v1"

/** Constructor injection by hand: one instance of each adapter for the process (lld.md#android). */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clock: () -> Long = System::currentTimeMillis
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val baseUrl = BuildConfig.API_BASE_URL.toHttpUrl()

    val session = SessionManager(HttpAuthApi(http, baseUrl), KeystoreTokenVault(app), clock, deviceName = Build.MODEL.take(80).ifBlank { "Android device" })
    // Sync removing or restricting an email stops its downloads too; set once the repositories exist (below).
    private var filesDropped: FilesDropped? = null
    private val cache = LocalCache(app) { ids -> filesDropped?.dropped(ids) }
    val scheduler = WorkSyncScheduler(app)
    private val api = HttpApi(http, baseUrl, session)
    val syncEngine = SyncEngine(api, cache.store, clock)
    val concerns = ConcernRepository(cache.source, cache.store, scheduler, clock)
    val archive = ArchiveRepository(api, cache.archive, cache.source, clock)
    val briefing = BriefingRepository(api, cache.briefing, clock)
    val chat = ChatRepository(api, cache.chat, clock)

    // Attachment bytes and exports stream for longer than a JSON call, so they get a per-read timeout instead of the 30 s call limit.
    private val streamingHttp = http.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val contentApi = HttpExportApi(streamingHttp, baseUrl, session)
    val attachments = AttachmentRepository(contentApi, cache.files, cache.blobs, FileCipher(KeystoreFileKeys()), WorkDownloadScheduler(app), clock)
    val exports = ExportRepository(contentApi, cache.exports, WorkExportScheduler(app), clock)

    // Text read from PDFs and images on this phone (its own text layer, bundled recognition); the text goes to the Worker and nowhere else.
    private val extractionScheduler = WorkExtractionScheduler(app)
    val extractor = DeviceExtractor(
        api = HttpExtractionApi(http, baseUrl, session),
        content = contentApi,
        opener = PlatformDocumentOpener(app, MlKitTextRecognizer()),
        capabilities = ExtractionCapabilities(pdfText = PDF_TEXT_LAYER_AVAILABLE, ocr = true, extractorVersion = EXTRACTOR_VERSION),
        conditions = AndroidRunConditions(app),
        workDir = File(app.noBackupFilesDir, "extraction"),
        clock = clock,
    )
    private val sessionEnd = SessionEnd(attachments, exports, extractor, extractionScheduler)

    // Local inference (lld.md#triage): triage and chat tasks the Worker leases to this phone, run by a model the owner installs and
    // that matches the pinned manifest. Without a manifest or a model nothing is claimed.
    val probe = DeviceProbe(app)
    val models = ModelStore(app, BuildConfig.MODEL_MANIFEST_B64.takeIf { it.isNotEmpty() }?.let { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)) })
    val deviceWork = DeviceWorkRunner(HttpDeviceWorkApi(http, baseUrl, session), models::model, probe::readiness)
    private val foregroundWork = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Schedules the charging background runs when a verified model is installed, and stops them when there is none. */
    fun afterModelChange() {
        if (models.state.value is ModelState.Installed) DeviceWorkWorker.schedule(app) else DeviceWorkWorker.cancel(app)
    }

    /**
     * Offers the phone for interactive work while the app is open, for a chat turn waiting on local-only evidence. Whatever
     * happens shows up through the turn's state at the Worker, not through this call.
     */
    fun runLocalChatNow() {
        if (models.state.value !is ModelState.Installed) return
        foregroundWork.launch { deviceWork.runOnce(WorkKind.INTERACTIVE) }
    }
    private var extractionTicks: Job? = null

    /** A deleted email's private copies go in one step: its downloads stop and its files go, then rows, text and answers, and sync is asked for the tombstone. */
    private val purgeDeleted = DeletedSourcePurge { ids, nowMs ->
        attachments.purgeSources(ids)
        cache.purge.purge(ids, nowMs)
        scheduler.requestNow()
    }
    val deletions = DeletionRepository(HttpReviewApi(http, baseUrl, session), cache.deletions, cache.archive, purgeDeleted, RecentAuthenticator { session.confirmRecent(it) }, clock)

    /** Work the owner started that must outlive the screen (an export download), tied to the process, not to a view model. */
    fun runInBackground(block: suspend () -> Unit) {
        background.launch { block() }
    }

    init {
        // Nothing is running yet, so whatever a killed process left behind is simply removed.
        extractor.purge()
        background.launch {
            // Start of the app: nothing is sent; interrupted work is requeued or marked, and files no row names are removed.
            attachments.recover()
            deletions.recover()
            exports.recover()
        }
        filesDropped = FilesDropped { ids -> attachments.stopDownloads(ids) }
        // Private files go and transfers stop when the session is gone (signed out, or the server refused or revoked it): the bytes
        // can be downloaded again. Text, queued edits, unsent items and the owner's deletion and export requests are kept for an
        // expired session; only explicit sign-out clears them.
        sessionEnd.watch(background, session.state)
    }

    /** Emits when a sync finishes, so chat can read again once instead of polling forever. */
    val syncTicks: Flow<Long?> = concerns.freshness().map { it.lastSyncedAtMs }.distinctUntilChanged()
    val push = HttpPushApi(http, baseUrl, session)

    /** Starts background sync for a signed-in owner. */
    fun startSync() {
        scheduler.schedulePeriodic()
        scheduler.requestNow()
        PushRegistrationWorker.enqueue(app)
        background.launch { attachments.resumeHeld() }
        extractionScheduler.schedulePeriodic()
        afterModelChange()
        // Files wait for the phone, so every finished sync is a reason to look again; the work itself is one run at a time.
        if (extractionTicks?.isActive != true) extractionTicks = background.launch { syncTicks.collect { extractionScheduler.requestNow() } }
    }

    /**
     * Sign-out cancels sync work and clears private cached state (lld.md#sync). Queued edits, unsent chat questions and
     * drafts are discarded with it; the confirmation names them first. Chat questions the Worker may still hold get one
     * bounded, best-effort cancel request while the session still exists: it is never waited on for long, it can only
     * stop work the Worker has not started, and nothing here reports it as having worked.
     */
    suspend fun signOut() {
        scheduler.cancelAll()
        PushRegistrationWorker.cancel(app)
        extractionTicks?.cancel()
        extractionScheduler.cancel()
        extractor.purge()
        DeviceWorkWorker.cancel(app)
        // Downloads and exports stop and their files go first; answers that arrive later are discarded by their repositories.
        exports.invalidate()
        deletions.invalidate()
        attachments.purgeAll()
        // A request in flight cannot be interrupted, so the cancels run on their own scope and sign-out only waits for them
        // up to the limit; whatever has not finished then is left to fail against the ended session.
        val cancelling = background.async { chat.cancelWaiting() }
        withTimeoutOrNull(CANCEL_ON_SIGN_OUT_MS) { cancelling.await() }
        session.signOut()
        // Reads still in flight must not write into the cache after it is cleared.
        briefing.invalidate()
        chat.invalidate()
        cache.clear()
        cache.blobs.deleteAll()
    }
}
