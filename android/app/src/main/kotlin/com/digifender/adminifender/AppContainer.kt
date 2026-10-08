package com.digifender.adminifender

import android.content.Context
import android.os.Build
import com.digifender.adminifender.core.data.local.LocalCache
import com.digifender.adminifender.core.data.network.HttpApi
import com.digifender.adminifender.core.data.platform.KeystoreTokenVault
import com.digifender.adminifender.core.data.platform.WorkSyncScheduler
import com.digifender.adminifender.core.data.push.HttpPushApi
import com.digifender.adminifender.push.PushRegistrationWorker
import com.digifender.adminifender.core.data.repository.ArchiveRepository
import com.digifender.adminifender.core.data.repository.BriefingRepository
import com.digifender.adminifender.core.data.repository.ChatRepository
import com.digifender.adminifender.core.data.repository.ConcernRepository
import com.digifender.adminifender.core.data.session.HttpAuthApi
import com.digifender.adminifender.core.data.session.SessionManager
import com.digifender.adminifender.core.data.sync.SyncEngine
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** How long sign-out waits, in all, for the best-effort cancel requests; it never blocks longer than this. */
private const val CANCEL_ON_SIGN_OUT_MS = 5_000L

/** Constructor injection by hand: one instance of each adapter for the process (lld.md#android). */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
    val briefing = BriefingRepository(api, cache.briefing, clock)
    val chat = ChatRepository(api, cache.chat, clock)

    /** Emits when a sync finishes, so chat can read again once instead of polling forever. */
    val syncTicks: Flow<Long?> = concerns.freshness().map { it.lastSyncedAtMs }.distinctUntilChanged()
    val push = HttpPushApi(http, baseUrl, session)

    /** Starts background sync for a signed-in owner. */
    fun startSync() {
        scheduler.schedulePeriodic()
        scheduler.requestNow()
        PushRegistrationWorker.enqueue(app)
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
        // A request in flight cannot be interrupted, so the cancels run on their own scope and sign-out only waits for them
        // up to the limit; whatever has not finished then is left to fail against the ended session.
        val cancelling = background.async { chat.cancelWaiting() }
        withTimeoutOrNull(CANCEL_ON_SIGN_OUT_MS) { cancelling.await() }
        session.signOut()
        // Reads still in flight must not write into the cache after it is cleared.
        briefing.invalidate()
        chat.invalidate()
        cache.clear()
    }
}
