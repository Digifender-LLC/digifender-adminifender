package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.session.AuthApi
import com.digifender.adminifender.core.data.session.AuthResult
import com.digifender.adminifender.core.data.session.IssuedTokens
import com.digifender.adminifender.core.data.session.PasskeyChallenge
import com.digifender.adminifender.core.data.session.PasskeyPurpose
import com.digifender.adminifender.core.data.session.PasskeyResult
import com.digifender.adminifender.core.data.session.RecentAuthOutcome
import com.digifender.adminifender.core.data.session.SessionManager
import com.digifender.adminifender.core.data.session.SessionState
import com.digifender.adminifender.core.data.session.SignInOutcome
import com.digifender.adminifender.core.data.session.SignedIn
import com.digifender.adminifender.core.data.session.StoredSession
import com.digifender.adminifender.core.data.session.TokenVault
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val LOGOUT_DEVICE = "11111111-1111-4111-8111-111111111111"
private const val LOGOUT_NOW = 1_000_000L
private fun logoutTokens(number: Int, expired: Boolean = false) = IssuedTokens("synthetic-access-$number", "synthetic-refresh-$number", if (expired) LOGOUT_NOW else LOGOUT_NOW + 900_000, LOGOUT_NOW + 2_000_000)

private class LogoutVault(var stored: StoredSession?) : TokenVault {
    var saveGate: CompletableDeferred<Unit>? = null
    var saveFailure: IOException? = null
    var loadFailure: IOException? = null
    var writeBeforeFailure = false
    override suspend fun load(): StoredSession? { loadFailure?.let { throw it }; return stored }
    override suspend fun save(session: StoredSession) {
        saveGate?.await()
        if (writeBeforeFailure) stored = session
        saveFailure?.let { throw it }
        stored = session
    }
}

private class LogoutApi : AuthApi {
    var refreshGate: CompletableDeferred<Unit>? = null
    var verifyGate: CompletableDeferred<Unit>? = null
    var recentGate: CompletableDeferred<Unit>? = null
    var refreshFailure: IOException? = null
    var logoutFailure: IOException? = null
    var refreshes = 0
    var options = 0
    val revoked = mutableListOf<String>()
    var refreshResult: AuthResult<IssuedTokens> = AuthResult.Ok(logoutTokens(2))
    override suspend fun passkeyOptions(purpose: PasskeyPurpose, deviceId: String?, accessToken: String?): AuthResult<PasskeyChallenge> {
        options++
        return AuthResult.Ok(PasskeyChallenge("synthetic-challenge", "{}"))
    }
    override suspend fun signInVerify(challengeId: String, assertion: JsonObject, deviceName: String, capabilities: JsonObject): AuthResult<SignedIn> {
        verifyGate?.await()
        return AuthResult.Ok(SignedIn(LOGOUT_DEVICE, logoutTokens(3)))
    }
    override suspend fun recentVerify(challengeId: String, assertion: JsonObject): AuthResult<Long> {
        recentGate?.await()
        return AuthResult.Ok(LOGOUT_NOW)
    }
    override suspend fun refresh(refreshToken: String): AuthResult<IssuedTokens> {
        refreshes++
        refreshGate?.await()
        refreshFailure?.let { throw it }
        return refreshResult
    }
    override suspend fun logout(accessToken: String): AuthResult<Unit> {
        revoked += accessToken
        logoutFailure?.let { throw it }
        return AuthResult.Ok(Unit)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionLogoutFailureTest {
    private val api = LogoutApi()
    private fun manager(vault: LogoutVault) = SessionManager(api, vault, { LOGOUT_NOW }, "Synthetic device")
    private fun vault(expired: Boolean = false) = LogoutVault(StoredSession(LOGOUT_DEVICE, logoutTokens(1, expired)))
    private val assertion = PasskeyResult.Assertion("""{"id":"synthetic-credential"}""")

    @Test fun expiredAccessRefreshRunsOnlyAfterDurableLocalRemoval() = runTest {
        val vault = vault(expired = true)
        val sessions = manager(vault)
        api.refreshGate = CompletableDeferred()
        val logout = launch { sessions.signOut() }
        runCurrent()
        assertEquals(StoredSession(LOGOUT_DEVICE, null), vault.stored)
        assertEquals(SessionState.SignedOut, sessions.state.value)
        assertNull(sessions.current())
        assertEquals(1, api.refreshes)
        api.refreshGate!!.complete(Unit)
        logout.join()
        assertTrue(api.revoked.contains("synthetic-access-2"))
        assertNull(vault.stored?.tokens)
    }

    @Test fun unexpectedRemoteFailuresCannotRetainOrRestoreLocalCredentials() = runTest {
        for (expired in listOf(false, true)) {
            val vault = vault(expired)
            val sessions = manager(vault)
            api.refreshFailure = IOException("synthetic-remote-failure")
            api.logoutFailure = IOException("synthetic-remote-failure")
            sessions.signOut()
            assertNull(vault.stored?.tokens)
            assertNull(sessions.current())
            assertEquals(SessionState.SignedOut, sessions.state.value)
        }
    }

    @Test fun failedVaultRemovalClosesMemorySurfacesFailureAndBlocksSignInUntilRetry() = runTest {
        val vault = vault()
        val sessions = manager(vault)
        sessions.restore()
        vault.saveFailure = IOException("synthetic-write-failure")
        assertTrue(runCatching { sessions.signOut() }.exceptionOrNull() is IOException)
        assertTrue(vault.stored?.tokens != null) // The failure is not misrepresented as durable removal.
        assertEquals(SessionState.SignedOut, sessions.state.value)
        assertNull(sessions.current())
        assertEquals(SignInOutcome.Unavailable("local_cleanup_required"), sessions.signIn { error("no prompt during incomplete cleanup") })
        assertEquals(0, api.options)
        assertTrue(api.revoked.isEmpty())
        vault.saveFailure = null
        sessions.signOut()
        assertNull(vault.stored?.tokens)
        assertEquals(SignInOutcome.SignedIn(LOGOUT_DEVICE), sessions.signIn { assertion })
        assertEquals("synthetic-access-3", sessions.current())
    }

    @Test fun writeThatPersistsThenThrowsStillRequiresExplicitCleanupRetry() = runTest {
        val vault = vault()
        val sessions = manager(vault)
        vault.writeBeforeFailure = true
        vault.saveFailure = IOException("synthetic-uncertain-write")
        assertTrue(runCatching { sessions.signOut() }.exceptionOrNull() is IOException)
        assertNull(vault.stored?.tokens)
        assertNull(sessions.current())
        assertEquals(SignInOutcome.Unavailable("local_cleanup_required"), sessions.signIn { assertion })
        vault.saveFailure = null
        sessions.signOut()
        assertEquals(SignInOutcome.SignedIn(LOGOUT_DEVICE), sessions.signIn { assertion })
    }

    @Test fun cancellationDuringVaultWriteCannotSkipLocalRemoval() = runTest {
        val vault = vault()
        val sessions = manager(vault)
        vault.saveGate = CompletableDeferred()
        val logout = launch { sessions.signOut() }
        runCurrent()
        assertNull(sessions.current())
        logout.cancel()
        vault.saveGate!!.complete(Unit)
        logout.join()
        assertTrue(logout.isCancelled)
        assertNull(vault.stored?.tokens)
        assertNull(sessions.current())
        assertEquals(SessionState.SignedOut, sessions.state.value)
    }

    @Test fun cancellationDuringRemoteRefreshLeavesRemovalComplete() = runTest {
        val vault = vault(expired = true)
        val sessions = manager(vault)
        api.refreshGate = CompletableDeferred()
        val logout = launch { sessions.signOut() }
        runCurrent()
        logout.cancel()
        logout.join()
        assertNull(vault.stored?.tokens)
        assertNull(sessions.current())
        assertTrue(api.revoked.isEmpty())
    }

    @Test fun alreadyRunningRefreshCannotIssueTokensOnceLogoutBegins() = runTest {
        val vault = vault(expired = true)
        val sessions = manager(vault)
        api.refreshGate = CompletableDeferred()
        val refreshing = async { sessions.current() }
        runCurrent()
        val logout = launch { sessions.signOut() }
        runCurrent()
        assertEquals(SessionState.SignedOut, sessions.state.value)
        assertNull(sessions.current()) // Must not wait for the refresh that still owns the mutex.
        api.refreshGate!!.complete(Unit)
        assertNull(refreshing.await())
        logout.join()
        assertNull(vault.stored?.tokens)
        assertNull(sessions.current())
    }

    @Test fun latePasskeyVerificationCannotReopenSignedOutSession() = runTest {
        val vault = vault()
        val sessions = manager(vault)
        api.verifyGate = CompletableDeferred()
        val signIn = async { sessions.signIn { assertion } }
        runCurrent()
        sessions.signOut()
        api.verifyGate!!.complete(Unit)
        assertEquals(SignInOutcome.Unavailable("session_changed"), signIn.await())
        assertNull(vault.stored?.tokens)
        assertNull(sessions.current())
        assertEquals(SessionState.SignedOut, sessions.state.value)
        assertTrue(api.revoked.contains("synthetic-access-3"))
    }

    @Test fun logoutDuringSignInPersistenceCannotPublishSignedInState() = runTest {
        val vault = vault()
        val sessions = manager(vault)
        vault.saveGate = CompletableDeferred()
        val signIn = async { sessions.signIn { assertion } }
        runCurrent()
        val logout = launch { sessions.signOut() }
        runCurrent()
        assertEquals(SessionState.SignedOut, sessions.state.value)
        assertNull(sessions.current())
        vault.saveGate!!.complete(Unit)
        assertEquals(SignInOutcome.Unavailable("session_changed"), signIn.await())
        logout.join()
        assertNull(vault.stored?.tokens)
        assertEquals(SessionState.SignedOut, sessions.state.value)
    }

    @Test fun unreadableVaultStillGetsOverwrittenWithTokenlessRecord() = runTest {
        val vault = vault()
        val sessions = manager(vault)
        vault.loadFailure = IOException("synthetic-read-failure")
        sessions.signOut()
        assertEquals(StoredSession(null, null), vault.stored)
        assertNull(sessions.current())
    }

    @Test fun uncertainRotatedTokenWriteNeverReplaysConsumedRefreshInThisProcess() = runTest {
        for (persisted in listOf(false, true)) {
            val vault = vault(expired = true)
            val sessions = manager(vault)
            vault.writeBeforeFailure = persisted
            vault.saveFailure = IOException("synthetic-uncertain-write")
            val before = api.refreshes
            assertTrue(runCatching { sessions.current() }.exceptionOrNull() is IOException)
            assertEquals(SessionState.SignInRequired, sessions.state.value)
            assertNull(sessions.current())
            assertNull(sessions.current())
            assertEquals(before + 1, api.refreshes)
            assertEquals(if (persisted) "synthetic-refresh-2" else "synthetic-refresh-1", vault.stored?.tokens?.refreshToken)
            vault.saveFailure = null
            assertEquals(SignInOutcome.SignedIn(LOGOUT_DEVICE), sessions.signIn { assertion })
        }
    }

    @Test fun failedRemovalOfRefusedCredentialCannotRetryItFromMemory() = runTest {
        val vault = vault(expired = true)
        val sessions = manager(vault)
        api.refreshResult = AuthResult.Rejected("unauthenticated")
        vault.saveFailure = IOException("synthetic-write-failure")
        assertTrue(runCatching { sessions.current() }.exceptionOrNull() is IOException)
        assertEquals(SessionState.SignInRequired, sessions.state.value)
        assertNull(sessions.current())
        assertEquals(1, api.refreshes)
    }

    @Test fun recentAuthenticationFinishingAfterLogoutIsNotConfirmed() = runTest {
        val sessions = manager(vault())
        api.recentGate = CompletableDeferred()
        val confirmation = async { sessions.confirmRecent { assertion } }
        runCurrent()
        sessions.signOut()
        api.recentGate!!.complete(Unit)
        assertEquals(RecentAuthOutcome.SignInRequired, confirmation.await())
        assertFalse(sessions.state.value is SessionState.SignedIn)
    }
}
