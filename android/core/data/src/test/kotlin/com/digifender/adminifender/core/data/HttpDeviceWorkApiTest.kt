package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.devicework.*
import com.digifender.adminifender.core.data.network.AccessTokens
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HttpDeviceWorkApiTest {
    private val server = MockWebServer()
    private val id = "11111111-1111-4111-8111-111111111111"
    private val body = ReadinessRequest(false, true, 70, "none", null, null)
    private var rejected = false
    private lateinit var api: HttpDeviceWorkApi

    @Before fun start() {
        server.start()
        api = HttpDeviceWorkApi(OkHttpClient(), server.url("/"), object : AccessTokens {
            override suspend fun current() = "synthetic-access-token"
            override suspend fun rejected(token: String) { rejected = true }
        })
    }
    @After fun stop() = server.close()
    private fun respond(code: Int, text: String) = server.enqueue(MockResponse.Builder().code(code).body(text).build())

    @Test fun readinessIncludesRequiredNullModelFieldsAndMutationKey() = runTest {
        respond(200, """{"data":{"ready":false}}""")
        assertEquals(DeviceCall.Ok(ReadinessResponse(false)), api.readiness(body, id))
        val request = server.takeRequest()
        assertEquals("/api/v1/device-work/readiness", request.url.encodedPath)
        assertEquals("Bearer synthetic-access-token", request.headers["authorization"])
        assertEquals(id, request.headers["idempotency-key"])
        val encoded = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(JsonNull, encoded["modelId"])
        assertEquals(JsonNull, encoded["modelManifestHash"])
    }

    @Test fun representsNoWorkAndLeaseRefusalWithoutSuccess() = runTest {
        respond(200, """{"data":{"work":null,"reason":"not_ready"}}""")
        assertEquals(DeviceCall.Ok(ClaimResponse(reason = "not_ready")), api.claim(body, id))
        respond(409, """{"error":{"code":"lease_lost","message":"stale","retryable":false}}""")
        assertEquals(DeviceCall.Rejected("lease_lost"), api.heartbeat(id, HeartbeatRequest(id), id))
    }

    @Test fun malformedSuccessAndAuthenticationLossAreExplicit() = runTest {
        respond(200, """{"data":{"notReady":false}}""")
        assertEquals(DeviceCall.Unavailable("malformed_response"), api.readiness(body, id))
        respond(401, "unauthorized")
        assertEquals(DeviceCall.SessionExpired, api.readiness(body, id))
        assertTrue(rejected)
    }

    @Test fun rejectsIdentifiersAndOversizedResponses() = runTest {
        assertEquals(DeviceCall.Rejected("invalid_attempt"), api.heartbeat("../result", HeartbeatRequest(id), id))
        assertEquals(0, server.requestCount)
        respond(200, "x".repeat(256 * 1024 + 1))
        assertEquals(DeviceCall.Unavailable("response_too_large"), api.readiness(body, id))
    }

    @Test fun neverFollowsRedirectedSourceBearingMutations() = runTest {
        server.enqueue(MockResponse.Builder().code(307).addHeader("location", server.url("/unexpected-origin").toString()).body("redirect").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"data":{"attemptId":"$id","state":"succeeded"}}""").build())
        val submitted = InferenceSubmission(id, "a".repeat(64), 1, "{\"syntheticSource\":\"private\"}", "b".repeat(64))
        assertEquals(DeviceCall.Rejected("unreadable_error"), api.submit(id, submitted, id))
        assertEquals(1, server.requestCount)
    }
}
