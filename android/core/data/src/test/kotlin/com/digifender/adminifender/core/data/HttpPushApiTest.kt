package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.AccessTokens
import com.digifender.adminifender.core.data.push.HttpPushApi
import com.digifender.adminifender.core.data.push.PushPermission
import com.digifender.adminifender.core.data.push.PushRegisterResult
import com.digifender.adminifender.core.data.push.registrationDigest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

class HttpPushApiTest {
    private val server = MockWebServer()
    private val device = "0b4e6f8a-2c3d-4e5f-8a9b-1c2d3e4f5a6b"
    private val token = "synthetic-fcm-token_0123456789:abcdefghijklmnop"
    private val refused = mutableListOf<String>()
    private lateinit var api: HttpPushApi

    @Before
    fun start() {
        server.start()
        val tokens = object : AccessTokens {
            override suspend fun current() = "access-token"
            override suspend fun rejected(token: String) {
                refused += token
            }
        }
        api = HttpPushApi(OkHttpClient(), server.url("/"), tokens)
    }

    @After
    fun stop() = server.close()

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code).addHeader("content-type", "application/json").body(body).build()

    @Test
    fun registersThisDeviceWithAnIdempotencyKey() = runTest {
        server.enqueue(json(200, """{"data":{"deviceId":"$device","push":"granted"},"requestId":"r"}"""))
        assertEquals(PushRegisterResult.Registered, api.register(device, token, PushPermission.GRANTED, "0.1.0", "key-1"))
        val request = server.takeRequest()
        assertEquals("/api/v1/devices/$device/push", request.url.encodedPath)
        assertEquals("Bearer access-token", request.headers["authorization"])
        assertEquals("key-1", request.headers["idempotency-key"])
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(mapOf("token" to JsonPrimitive(token), "permission" to JsonPrimitive("granted"), "appVersion" to JsonPrimitive("0.1.0")), body)
    }

    @Test
    fun mapsRefusalsSessionsAndOutages() = runTest {
        server.enqueue(json(403, """{"error":{"code":"wrong_device","message":"m"}}"""))
        assertEquals(PushRegisterResult.Rejected("wrong_device"), api.register(device, token, PushPermission.DENIED, "0.1.0", "k"))
        server.enqueue(json(401, """{"error":{"code":"unauthenticated","message":"m"}}"""))
        assertEquals(PushRegisterResult.SessionExpired, api.register(device, token, PushPermission.DENIED, "0.1.0", "k"))
        assertEquals(listOf("access-token"), refused)
        server.enqueue(json(503, """{"error":{"code":"unavailable","message":"m"}}"""))
        assertEquals(PushRegisterResult.Unavailable("unavailable"), api.register(device, token, PushPermission.DENIED, "0.1.0", "k"))
        server.close()
        assertEquals(PushRegisterResult.Unavailable("network"), api.register(device, token, PushPermission.DENIED, "0.1.0", "k"))
    }

    @Test
    fun anyChangedFieldChangesTheDigest() {
        val base = registrationDigest(device, token, PushPermission.GRANTED, "0.1.0")
        assertEquals(base, registrationDigest(device, token, PushPermission.GRANTED, "0.1.0"))
        assertNotEquals(base, registrationDigest(device, token + "x", PushPermission.GRANTED, "0.1.0"))
        assertNotEquals(base, registrationDigest(device, token, PushPermission.DENIED, "0.1.0"))
        assertNotEquals(base, registrationDigest(device, token, PushPermission.GRANTED, "0.2.0"))
        assertNotEquals(base, registrationDigest("other", token, PushPermission.GRANTED, "0.1.0"))
    }
}
