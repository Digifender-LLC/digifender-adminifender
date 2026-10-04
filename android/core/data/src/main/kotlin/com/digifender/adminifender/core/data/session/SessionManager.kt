package com.digifender.adminifender.core.data.session

import com.digifender.adminifender.core.data.network.AccessTokens
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

sealed interface SessionState {
    /** The vault has not been read yet. */
    data object Unknown : SessionState

    /** Never signed in, or signed out on purpose. */
    data object SignedOut : SessionState

    /** The server refused the session (expired, revoked or replayed); a passkey sign-in is needed. */
    data object SignInRequired : SessionState

    data class SignedIn(val deviceId: String) : SessionState
}

sealed interface SignInOutcome {
    data class SignedIn(val deviceId: String) : SignInOutcome

    data object Cancelled : SignInOutcome

    data object NoPasskey : SignInOutcome

    data class Rejected(val code: String) : SignInOutcome

    data class Unavailable(val code: String) : SignInOutcome
}

sealed interface RecentAuthOutcome {
    data class Confirmed(val recentAuthAt: Long) : RecentAuthOutcome

    data object Cancelled : RecentAuthOutcome

    data object SignInRequired : RecentAuthOutcome

    data class Rejected(val code: String) : RecentAuthOutcome

    data class Unavailable(val code: String) : RecentAuthOutcome
}

/**
 * Owns the device session: restores it from the [TokenVault], hands out access tokens, and rotates them through one
 * refresh at a time. A rotated refresh token is single-use on the server and replaying it revokes the whole family,
 * so a refresh is never cancelled halfway and its result is saved before any caller sees it.
 */
class SessionManager(
    private val api: AuthApi,
    private val vault: TokenVault,
    private val clock: () -> Long,
    private val deviceName: String,
    private val capabilities: JsonObject = JsonObject(emptyMap()),
) : AccessTokens {
    private val mutex = Mutex()
    private var loaded = false
    private var session: StoredSession? = null
    private val _state = MutableStateFlow<SessionState>(SessionState.Unknown)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private suspend fun loadLocked(): StoredSession? {
        if (!loaded) {
            session = vault.load()
            loaded = true
            val restored = session
            _state.value = if (restored?.tokens != null && restored.deviceId != null) SessionState.SignedIn(restored.deviceId) else SessionState.SignedOut
        }
        return session
    }

    private suspend fun storeLocked(next: StoredSession) {
        vault.save(next)
        session = next
    }

    /** Reads the vault so [state] reflects the saved session. */
    suspend fun restore(): SessionState = mutex.withLock {
        loadLocked()
        _state.value
    }

    override suspend fun current(): String? = mutex.withLock {
        val stored = loadLocked() ?: return null
        val tokens = stored.tokens ?: return null
        val now = clock()
        if (tokens.accessExpiresAt - EXPIRY_MARGIN_MS > now) return tokens.accessToken
        refreshLocked(stored, tokens, now)?.accessToken
    }

    override suspend fun rejected(token: String) {
        mutex.withLock {
            val stored = loadLocked() ?: return
            val tokens = stored.tokens ?: return
            // Only the token that failed is marked stale; a newer one from a concurrent refresh stays usable.
            if (tokens.accessToken == token) session = stored.copy(tokens = tokens.copy(accessExpiresAt = 0))
        }
    }

    private suspend fun refreshLocked(stored: StoredSession, tokens: IssuedTokens, now: Long): IssuedTokens? {
        if (tokens.refreshExpiresAt <= now) {
            requireSignInLocked(stored)
            return null
        }
        return withContext(NonCancellable) {
            when (val result = api.refresh(tokens.refreshToken)) {
                is AuthResult.Ok -> {
                    storeLocked(stored.copy(tokens = result.value))
                    result.value
                }
                is AuthResult.Rejected -> {
                    requireSignInLocked(stored)
                    null
                }
                // Keep the refresh token: the server may not have seen the request, and a later attempt decides.
                is AuthResult.Unavailable -> null
            }
        }
    }

    private suspend fun requireSignInLocked(stored: StoredSession) {
        storeLocked(stored.copy(tokens = null))
        _state.value = SessionState.SignInRequired
    }

    /** Passkey sign-in. The prompt runs outside the lock so token reads are not blocked while the owner decides. */
    suspend fun signIn(passkeys: PasskeyAuthenticator): SignInOutcome {
        val deviceId = mutex.withLock { loadLocked()?.deviceId }
        val challenge = when (val r = api.passkeyOptions(PasskeyPurpose.SIGN_IN, deviceId, null)) {
            is AuthResult.Ok -> r.value
            is AuthResult.Rejected -> return SignInOutcome.Rejected(r.code)
            is AuthResult.Unavailable -> return SignInOutcome.Unavailable(r.code)
        }
        val assertion = when (val p = assertion(passkeys, challenge)) {
            is Prompted.Ok -> p.assertion
            Prompted.Cancelled -> return SignInOutcome.Cancelled
            Prompted.NoCredential -> return SignInOutcome.NoPasskey
            is Prompted.Failed -> return SignInOutcome.Rejected(p.code)
        }
        return when (val r = api.signInVerify(challenge.challengeId, assertion, deviceName, capabilities)) {
            is AuthResult.Ok -> withContext(NonCancellable) {
                mutex.withLock {
                    loadLocked()
                    storeLocked(StoredSession(r.value.deviceId, r.value.tokens))
                    _state.value = SessionState.SignedIn(r.value.deviceId)
                }
                SignInOutcome.SignedIn(r.value.deviceId)
            }
            is AuthResult.Rejected -> SignInOutcome.Rejected(r.code)
            is AuthResult.Unavailable -> SignInOutcome.Unavailable(r.code)
        }
    }

    /** Upgrades the current session to recent passkey authentication for reviewed effects (lld.md#reviews-external-effects-and-readback). */
    suspend fun confirmRecent(passkeys: PasskeyAuthenticator): RecentAuthOutcome {
        val token = current() ?: return RecentAuthOutcome.SignInRequired
        val challenge = when (val r = api.passkeyOptions(PasskeyPurpose.RECENT, null, token)) {
            is AuthResult.Ok -> r.value
            is AuthResult.Rejected -> return if (r.code == UNAUTHENTICATED) RecentAuthOutcome.SignInRequired else RecentAuthOutcome.Rejected(r.code)
            is AuthResult.Unavailable -> return RecentAuthOutcome.Unavailable(r.code)
        }
        val assertion = when (val p = assertion(passkeys, challenge)) {
            is Prompted.Ok -> p.assertion
            Prompted.Cancelled -> return RecentAuthOutcome.Cancelled
            Prompted.NoCredential -> return RecentAuthOutcome.Rejected("no_passkey")
            is Prompted.Failed -> return RecentAuthOutcome.Rejected(p.code)
        }
        return when (val r = api.recentVerify(challenge.challengeId, assertion)) {
            is AuthResult.Ok -> RecentAuthOutcome.Confirmed(r.value)
            is AuthResult.Rejected -> RecentAuthOutcome.Rejected(r.code)
            is AuthResult.Unavailable -> RecentAuthOutcome.Unavailable(r.code)
        }
    }

    /**
     * Forgets the tokens locally first, then revokes the session family on the server when it can. The device ID is
     * kept so signing in again reuses this device's registration.
     */
    suspend fun signOut() {
        val access = withContext(NonCancellable) {
            mutex.withLock {
                val stored = loadLocked()
                val tokens = stored?.tokens
                val now = clock()
                val usable = when {
                    tokens == null -> null
                    tokens.accessExpiresAt - EXPIRY_MARGIN_MS > now -> tokens.accessToken
                    tokens.refreshExpiresAt > now -> (api.refresh(tokens.refreshToken) as? AuthResult.Ok)?.value?.accessToken
                    else -> null
                }
                storeLocked(StoredSession(stored?.deviceId, null))
                _state.value = SessionState.SignedOut
                usable
            }
        }
        if (access != null) api.logout(access)
    }

    private sealed interface Prompted {
        data class Ok(val assertion: JsonObject) : Prompted

        data object Cancelled : Prompted

        data object NoCredential : Prompted

        data class Failed(val code: String) : Prompted
    }

    private suspend fun assertion(passkeys: PasskeyAuthenticator, challenge: PasskeyChallenge): Prompted = when (val p = passkeys.assert(challenge.optionsJson)) {
        is PasskeyResult.Assertion -> try {
            (Json.parseToJsonElement(p.responseJson) as? JsonObject)?.let { Prompted.Ok(it) } ?: Prompted.Failed("malformed_assertion")
        } catch (_: SerializationException) {
            Prompted.Failed("malformed_assertion")
        }
        PasskeyResult.Cancelled -> Prompted.Cancelled
        PasskeyResult.NoCredential -> Prompted.NoCredential
        is PasskeyResult.Failed -> Prompted.Failed(p.code)
    }

    companion object {
        /** Refresh this long before the server's expiry so a token does not lapse in flight. */
        const val EXPIRY_MARGIN_MS = 60_000L
        private const val UNAUTHENTICATED = "unauthenticated"
    }
}
