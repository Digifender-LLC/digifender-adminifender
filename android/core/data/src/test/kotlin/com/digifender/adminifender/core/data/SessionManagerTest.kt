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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val MINUTE = 60_000L
private const val DEVICE = "0b4e6f8a-2c3d-4e5f-8a9b-1c2d3e4f5a6b"

private class MemoryVault(var stored: StoredSession? = null) : TokenVault {
    var saves = 0
    override suspend fun load() = stored
    override suspend fun save(session: StoredSession) {
        saves++
        stored = session
    }
}

private class FakeAuthApi : AuthApi {
    val calls = mutableListOf<String>()
    var refreshResult: AuthResult<IssuedTokens> = AuthResult.Unavailable("network")
    var refreshGate: CompletableDeferred<Unit>? = null
    var signInResult: AuthResult<SignedIn> = AuthResult.Unavailable("network")
    var optionsResult: AuthResult<PasskeyChallenge> = AuthResult.Ok(PasskeyChallenge("c1", """{"challenge":"abc"}"""))
    var lastDeviceId: String? = null
    var lastBearer: String? = null
    var lastAssertion: JsonObject? = null

    override suspend fun passkeyOptions(purpose: PasskeyPurpose, deviceId: String?, accessToken: String?): AuthResult<PasskeyChallenge> {
        calls += "options:${purpose.wire}"
        lastDeviceId = deviceId
        lastBearer = accessToken
        return optionsResult
    }

    override suspend fun signInVerify(challengeId: String, assertion: JsonObject, deviceName: String, capabilities: JsonObject): AuthResult<SignedIn> {
        calls += "verify:$challengeId:$deviceName"
        lastAssertion = assertion
        return signInResult
    }

    override suspend fun recentVerify(challengeId: String, assertion: JsonObject): AuthResult<Long> {
        calls += "recent:$challengeId"
        return AuthResult.Ok(1234L)
    }

    override suspend fun refresh(refreshToken: String): AuthResult<IssuedTokens> {
        calls += "refresh:$refreshToken"
        refreshGate?.await()
        return refreshResult
    }

    override suspend fun logout(accessToken: String): AuthResult<Unit> {
        calls += "logout:$accessToken"
        return AuthResult.Ok(Unit)
    }
}

class SessionManagerTest {
    private var now = 1_000 * MINUTE
    private val api = FakeAuthApi()

    private fun tokens(n: Int, accessFor: Long = 15 * MINUTE, refreshFor: Long = 30 * 24 * 60 * MINUTE) =
        IssuedTokens("afa_$n", "afr_$n", now + accessFor, now + refreshFor)

    private fun manager(vault: TokenVault) = SessionManager(api, vault, { now }, "Pixel")

    @Test
    fun restoresAndUsesAValidAccessTokenWithoutRefreshing() = runTest {
        val sessions = manager(MemoryVault(StoredSession(DEVICE, tokens(1))))
        assertEquals(SessionState.SignedIn(DEVICE), sessions.restore())
        assertEquals("afa_1", sessions.current())
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test
    fun signedOutWithoutAVault() = runTest {
        val sessions = manager(MemoryVault())
        assertEquals(SessionState.SignedOut, sessions.restore())
        assertNull(sessions.current())
    }

    @Test
    fun concurrentCallersShareOneRefreshAndTheRotatedTokensAreSaved() = runTest {
        val vault = MemoryVault(StoredSession(DEVICE, tokens(1, accessFor = 30_000)))
        val sessions = manager(vault)
        api.refreshResult = AuthResult.Ok(tokens(2))
        val results = (1..5).map { async { sessions.current() } }.awaitAll()
        assertEquals(List(5) { "afa_2" }, results)
        assertEquals(listOf("refresh:afr_1"), api.calls)
        assertEquals(tokens(2), vault.stored?.tokens)
    }

    @Test
    fun refusedRefreshRequiresSignInAndKeepsTheDevice() = runTest {
        val vault = MemoryVault(StoredSession(DEVICE, tokens(1, accessFor = 0)))
        val sessions = manager(vault)
        api.refreshResult = AuthResult.Rejected("unauthenticated")
        assertNull(sessions.current())
        assertEquals(SessionState.SignInRequired, sessions.state.value)
        assertEquals(StoredSession(DEVICE, null), vault.stored)
        // No second attempt with the refused token.
        assertNull(sessions.current())
        assertEquals(1, api.calls.size)
    }

    @Test
    fun unavailableRefreshKeepsTheRefreshTokenForLater() = runTest {
        val vault = MemoryVault(StoredSession(DEVICE, tokens(1, accessFor = 0)))
        val sessions = manager(vault)
        assertNull(sessions.current())
        assertEquals(SessionState.SignedIn(DEVICE), sessions.state.value)
        assertEquals("afr_1", vault.stored?.tokens?.refreshToken)
        api.refreshResult = AuthResult.Ok(tokens(2))
        assertEquals("afa_2", sessions.current())
    }

    @Test
    fun expiredRefreshTokenNeedsSignInWithoutANetworkCall() = runTest {
        val sessions = manager(MemoryVault(StoredSession(DEVICE, tokens(1, accessFor = 0, refreshFor = 0))))
        assertNull(sessions.current())
        assertEquals(SessionState.SignInRequired, sessions.state.value)
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test
    fun aRejectedAccessTokenIsRefreshedButANewerOneIsKept() = runTest {
        val sessions = manager(MemoryVault(StoredSession(DEVICE, tokens(1))))
        assertEquals("afa_1", sessions.current())
        api.refreshResult = AuthResult.Ok(tokens(2))
        sessions.rejected("afa_1")
        assertEquals("afa_2", sessions.current())
        sessions.rejected("afa_1")
        assertEquals("afa_2", sessions.current())
        assertEquals(listOf("refresh:afr_1"), api.calls)
    }

    @Test
    fun aCancelledCallerDoesNotLoseRotatedTokens() = runTest {
        val vault = MemoryVault(StoredSession(DEVICE, tokens(1, accessFor = 0)))
        val sessions = manager(vault)
        api.refreshResult = AuthResult.Ok(tokens(2))
        api.refreshGate = CompletableDeferred()
        val caller = launch { sessions.current() }
        runCurrent()
        caller.cancel()
        api.refreshGate?.complete(Unit)
        caller.join()
        assertEquals(tokens(2), vault.stored?.tokens)
        assertEquals("afa_2", sessions.current())
        assertEquals(listOf("refresh:afr_1"), api.calls)
    }

    @Test
    fun signInReusesTheKnownDeviceAndSavesTheSession() = runTest {
        val vault = MemoryVault(StoredSession(DEVICE, null))
        val sessions = manager(vault)
        api.signInResult = AuthResult.Ok(SignedIn(DEVICE, tokens(3)))
        val outcome = sessions.signIn { request ->
            assertEquals("""{"challenge":"abc"}""", request)
            PasskeyResult.Assertion("""{"id":"cred","type":"public-key"}""")
        }
        assertEquals(SignInOutcome.SignedIn(DEVICE), outcome)
        assertEquals(DEVICE, api.lastDeviceId)
        assertEquals(listOf("options:sign_in", "verify:c1:Pixel"), api.calls)
        assertEquals(JsonPrimitive("cred"), api.lastAssertion?.get("id"))
        assertEquals(StoredSession(DEVICE, tokens(3)), vault.stored)
        assertEquals(SessionState.SignedIn(DEVICE), sessions.state.value)
        assertEquals("afa_3", sessions.current())
    }

    @Test
    fun cancelledOrMissingPasskeysChangeNothing() = runTest {
        val vault = MemoryVault()
        val sessions = manager(vault)
        assertEquals(SignInOutcome.Cancelled, sessions.signIn { PasskeyResult.Cancelled })
        assertEquals(SignInOutcome.NoPasskey, sessions.signIn { PasskeyResult.NoCredential })
        assertEquals(SignInOutcome.Rejected("malformed_assertion"), sessions.signIn { PasskeyResult.Assertion("[1]") })
        assertEquals(0, vault.saves)
        assertEquals(listOf("options:sign_in", "options:sign_in", "options:sign_in"), api.calls)
    }

    @Test
    fun recentAuthUsesTheCurrentSession() = runTest {
        val sessions = manager(MemoryVault(StoredSession(DEVICE, tokens(1))))
        val outcome = sessions.confirmRecent { PasskeyResult.Assertion("""{"id":"cred"}""") }
        assertEquals(RecentAuthOutcome.Confirmed(1234L), outcome)
        assertEquals("afa_1", api.lastBearer)
        assertEquals(listOf("options:recent", "recent:c1"), api.calls)
        assertEquals(RecentAuthOutcome.SignInRequired, manager(MemoryVault()).confirmRecent { error("no prompt when signed out") })
    }

    @Test
    fun signOutForgetsTokensThenRevokesOnTheServer() = runTest {
        val vault = MemoryVault(StoredSession(DEVICE, tokens(1)))
        val sessions = manager(vault)
        sessions.signOut()
        assertEquals(StoredSession(DEVICE, null), vault.stored)
        assertEquals(SessionState.SignedOut, sessions.state.value)
        assertEquals(listOf("logout:afa_1"), api.calls)
        assertNull(sessions.current())
    }

    @Test
    fun signOutWithAnExpiredAccessTokenRefreshesToRevoke() = runTest {
        val vault = MemoryVault(StoredSession(DEVICE, tokens(1, accessFor = 0)))
        val sessions = manager(vault)
        api.refreshResult = AuthResult.Ok(tokens(2))
        sessions.signOut()
        assertEquals(listOf("refresh:afr_1", "logout:afa_2"), api.calls)
        assertEquals(StoredSession(DEVICE, null), vault.stored)
    }
}
