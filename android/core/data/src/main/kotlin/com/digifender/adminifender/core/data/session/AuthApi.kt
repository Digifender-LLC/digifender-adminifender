package com.digifender.adminifender.core.data.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Opaque session credentials; expiry times are epoch milliseconds (lld.md#identity-and-owner-enrollment). */
@Serializable
data class IssuedTokens(val accessToken: String, val refreshToken: String, val accessExpiresAt: Long, val refreshExpiresAt: Long)

/** What the app keeps between launches. The device ID outlives sign-out so a later sign-in reuses the same device. */
@Serializable
data class StoredSession(val deviceId: String?, val tokens: IssuedTokens?)

enum class PasskeyPurpose(val wire: String) { SIGN_IN("sign_in"), RECENT("recent") }

/** A WebAuthn assertion challenge; [optionsJson] is passed unchanged to the platform passkey API. */
data class PasskeyChallenge(val challengeId: String, val optionsJson: String)

data class SignedIn(val deviceId: String, val tokens: IssuedTokens)

sealed interface AuthResult<out T> {
    data class Ok<T>(val value: T) : AuthResult<T>

    /** The server answered and refused; repeating the same request will not help. */
    data class Rejected(val code: String) : AuthResult<Nothing>

    /** No usable answer (network, 429, 5xx); the request may or may not have reached the server. */
    data class Unavailable(val code: String) : AuthResult<Nothing>
}

/** The `/auth` endpoints the Android app uses. Enrollment and recovery run on the HTTPS enrollment origin instead. */
interface AuthApi {
    suspend fun passkeyOptions(purpose: PasskeyPurpose, deviceId: String?, accessToken: String?): AuthResult<PasskeyChallenge>

    suspend fun signInVerify(challengeId: String, assertion: JsonObject, deviceName: String, capabilities: JsonObject): AuthResult<SignedIn>

    /** Returns the server's recent-auth time in epoch milliseconds. */
    suspend fun recentVerify(challengeId: String, assertion: JsonObject): AuthResult<Long>

    suspend fun refresh(refreshToken: String): AuthResult<IssuedTokens>

    suspend fun logout(accessToken: String): AuthResult<Unit>
}

/** Encrypted at-rest storage for [StoredSession]; implementations must keep it out of backups. */
interface TokenVault {
    suspend fun load(): StoredSession?

    suspend fun save(session: StoredSession)
}

sealed interface PasskeyResult {
    /** `PublicKeyCredential.toJSON()` output from the platform. */
    data class Assertion(val responseJson: String) : PasskeyResult

    data object Cancelled : PasskeyResult

    data object NoCredential : PasskeyResult

    data class Failed(val code: String) : PasskeyResult
}

/** Platform passkey prompt. The Credential Manager adapter needs an Activity, so callers pass one per prompt. */
fun interface PasskeyAuthenticator {
    suspend fun assert(requestJson: String): PasskeyResult
}
