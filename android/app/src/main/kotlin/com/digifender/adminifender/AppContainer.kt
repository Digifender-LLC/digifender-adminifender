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
    val briefing = BriefingRepository(api, cache.briefing, clock)
    val push = HttpPushApi(http, baseUrl, session)

    /** Starts background sync for a signed-in owner. */
    fun startSync() {
        scheduler.schedulePeriodic()
        scheduler.requestNow()
        PushRegistrationWorker.enqueue(app)
    }

    /** Sign-out cancels sync work and clears private cached state (lld.md#sync). Queued edits are discarded with it. */
    suspend fun signOut() {
        scheduler.cancelAll()
        PushRegistrationWorker.cancel(app)
        session.signOut()
        // Reads still in flight must not write into the cache after it is cleared.
        briefing.invalidate()
        cache.clear()
    }
}
