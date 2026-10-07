package com.digifender.adminifender.ui

import com.digifender.adminifender.core.data.session.*
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionCleanupViewModelTest {
    private val api = object : AuthApi {
        override suspend fun passkeyOptions(purpose: PasskeyPurpose, deviceId: String?, accessToken: String?): AuthResult<PasskeyChallenge> = error("unexpected")
        override suspend fun signInVerify(challengeId: String, assertion: JsonObject, deviceName: String, capabilities: JsonObject): AuthResult<SignedIn> = error("unexpected")
        override suspend fun recentVerify(challengeId: String, assertion: JsonObject): AuthResult<Long> = error("unexpected")
        override suspend fun refresh(refreshToken: String): AuthResult<IssuedTokens> = error("unexpected")
        override suspend fun logout(accessToken: String): AuthResult<Unit> = error("unexpected")
    }
    private val vault = object : TokenVault {
        var stored: StoredSession? = null
        override suspend fun load() = stored
        override suspend fun save(session: StoredSession) { stored = session }
    }
    private val session = SessionManager(api, vault, { 100 }, "synthetic")
    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    @Test fun cleanupFailureRemainsVisibleAfterCredentialsAreForgottenAndCanBeRetried() = runTest {
        var attempts = 0
        val model = SessionViewModel(session, {}, {
            session.signOut()
            if (++attempts == 1) throw IOException("synthetic cleanup failure")
        })
        model.signOut()
        assertEquals(SessionState.SignedOut, model.state.value.session)
        assertTrue(model.state.value.signOutFailed)
        assertFalse(model.state.value.busy)
        model.signOut()
        assertEquals(2, attempts)
        assertFalse(model.state.value.signOutFailed)
    }

    @Test fun incompleteCleanupResumesAtStartupWithoutRestartingPrivateWork() = runTest {
        var resumed = 0
        var starts = 0
        val model = SessionViewModel(session, { starts++ }, { resumed++; session.signOut() }, cleanupPending = { true })
        assertEquals(1, resumed)
        assertEquals(0, starts)
        assertEquals(SessionState.SignedOut, model.state.value.session)
    }

    @Test fun queuedDeadlinePreviewUsesItsStoredZone() {
        val at = java.time.Instant.parse("2026-10-07T12:30:00Z").toEpochMilli()
        assertTrue(pendingDateTime(at, "Asia/Tokyo").contains("21:30"))
        assertTrue(pendingDateTime(at, "UTC").contains("12:30"))
    }
}
