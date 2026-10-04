package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.session.AuthResult
import com.digifender.adminifender.core.data.session.HttpAuthApi
import com.digifender.adminifender.core.data.session.IssuedTokens
import com.digifender.adminifender.core.data.session.PasskeyChallenge
import com.digifender.adminifender.core.data.session.PasskeyPurpose
import com.digifender.adminifender.core.data.session.SignedIn
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class HttpAuthApiTest {
    private val server = MockWebServer()
    private lateinit var api: HttpAuthApi
    private val device = "0b4e6f8a-2c3d-4e5f-8a9b-1c2d3e4f5a6b"
    private val challenge = "5f0c2b1e-9d8a-4c7b-a6e5-d4c3b2a1f0e9"
    private val access = "afa_" + "a".repeat(43)
    private val refresh = "afr_" + "r".repeat(43)
    private val tokenJson = """"accessToken":"$access","refreshToken":"$refresh","accessExpiresAt":"2026-10-04T12:15:00.000Z","refreshExpiresAt":"2026-11-03T12:00:00.000Z""""
    private val issued = IssuedTokens(access, refresh, Instant.parse("2026-10-04T12:15:00Z").toEpochMilli(), Instant.parse("2026-11-03T12:00:00Z").toEpochMilli())

    @Before
    fun start() {
        server.start()
        api = HttpAuthApi(OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build(), server.url("/"))
    }

    @After
    fun stop() = server.close()

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code).addHeader("content-type", "application/json").body(body).build()

    private fun error(code: Int, errorCode: String) = json(code, """{"error":{"code":"$errorCode","message":"m","retryable":false},"requestId":"r"}""")

    private fun bodyOf(request: mockwebserver3.RecordedRequest): JsonObject = Json.parseToJsonElement(request.body!!.utf8()).jsonObject

    @Test
    fun signInOptionsCarryTheKnownDeviceAndReturnRawOptions() = runTest {
        server.enqueue(json(200, """{"data":{"challengeId":"$challenge","options":{"challenge":"abc","rpId":"example.test","userVerification":"required"}},"requestId":"r"}"""))
        val result = api.passkeyOptions(PasskeyPurpose.SIGN_IN, device, null) as AuthResult.Ok<PasskeyChallenge>
        assertEquals(challenge, result.value.challengeId)
        assertEquals(JsonPrimitive("abc"), Json.parseToJsonElement(result.value.optionsJson).jsonObject["challenge"])
        val request = server.takeRequest()
        assertEquals("/api/v1/auth/passkey/options", request.url.encodedPath)
        assertNull(request.headers["authorization"])
        assertEquals(mapOf("purpose" to JsonPrimitive("sign_in"), "deviceId" to JsonPrimitive(device)), bodyOf(request))
    }

    @Test
    fun recentOptionsAreAuthenticatedAndOmitTheDevice() = runTest {
        server.enqueue(json(200, """{"data":{"challengeId":"$challenge","options":{"challenge":"abc"}},"requestId":"r"}"""))
        api.passkeyOptions(PasskeyPurpose.RECENT, device, access)
        val request = server.takeRequest()
        assertEquals("Bearer $access", request.headers["authorization"])
        assertEquals(mapOf("purpose" to JsonPrimitive("recent")), bodyOf(request))
    }

    @Test
    fun signInVerifyParsesTheFlatTokenResponse() = runTest {
        server.enqueue(json(200, """{"data":{"deviceId":"$device",$tokenJson},"requestId":"r"}"""))
        val assertion = Json.parseToJsonElement("""{"id":"cred","type":"public-key"}""").jsonObject
        val result = api.signInVerify(challenge, assertion, "Pixel", JsonObject(emptyMap()))
        assertEquals(AuthResult.Ok(SignedIn(device, issued)), result)
        val body = bodyOf(server.takeRequest())
        assertEquals(setOf("challengeId", "assertion", "deviceCapabilities", "deviceName"), body.keys)
        assertEquals(assertion, body["assertion"])
    }

    @Test
    fun recentVerifyReturnsTheRecentAuthTime() = runTest {
        server.enqueue(json(200, """{"data":{"recentAuthAt":"2026-10-04T12:00:00.000Z"},"requestId":"r"}"""))
        assertEquals(AuthResult.Ok(Instant.parse("2026-10-04T12:00:00Z").toEpochMilli()), api.recentVerify(challenge, JsonObject(emptyMap())))
    }

    @Test
    fun refreshSendsTheRefreshSchemeAndParsesRotatedTokens() = runTest {
        server.enqueue(json(200, """{"data":{$tokenJson},"requestId":"r"}"""))
        assertEquals(AuthResult.Ok(issued), api.refresh(refresh))
        val request = server.takeRequest()
        assertEquals("/api/v1/auth/refresh", request.url.encodedPath)
        assertEquals("Refresh $refresh", request.headers["authorization"])
    }

    @Test
    fun refusalsAndOutagesAreDistinguished() = runTest {
        server.enqueue(error(401, "unauthenticated"))
        assertEquals(AuthResult.Rejected("unauthenticated"), api.refresh(refresh))
        server.enqueue(error(410, "challenge_expired"))
        assertEquals(AuthResult.Rejected("challenge_expired"), api.recentVerify(challenge, JsonObject(emptyMap())))
        server.enqueue(error(429, "rate_limited"))
        assertEquals(AuthResult.Unavailable("rate_limited"), api.passkeyOptions(PasskeyPurpose.SIGN_IN, null, null))
        server.enqueue(error(503, "unavailable"))
        assertEquals(AuthResult.Unavailable("unavailable"), api.refresh(refresh))
        server.enqueue(json(200, """{"data":{"accessToken":"$access"},"requestId":"r"}"""))
        assertEquals(AuthResult.Unavailable("malformed_response"), api.refresh(refresh))
        server.enqueue(json(200, """{"data":{"recentAuthAt":"yesterday"},"requestId":"r"}"""))
        assertEquals(AuthResult.Unavailable("malformed_response"), api.recentVerify(challenge, JsonObject(emptyMap())))
    }

    @Test
    fun aDroppedConnectionIsUnavailable() = runTest {
        server.close()
        assertEquals(AuthResult.Unavailable("network"), api.refresh(refresh))
    }

    @Test
    fun logoutUsesTheBearerToken() = runTest {
        server.enqueue(json(200, """{"data":{"loggedOut":true},"requestId":"r"}"""))
        assertEquals(AuthResult.Ok(Unit), api.logout(access))
        val request = server.takeRequest()
        assertEquals("/api/v1/auth/logout", request.url.encodedPath)
        assertEquals("Bearer $access", request.headers["authorization"])
    }
}
