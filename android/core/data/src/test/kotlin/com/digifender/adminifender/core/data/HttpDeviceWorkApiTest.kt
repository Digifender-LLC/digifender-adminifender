package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.device.WorkResult
import com.digifender.adminifender.core.data.network.AccessTokens
import com.digifender.adminifender.core.data.network.CallResult
import com.digifender.adminifender.core.data.network.HttpDeviceWorkApi
import com.digifender.adminifender.core.data.network.isUuid
import com.digifender.adminifender.core.data.network.parseDeclineRequest
import com.digifender.adminifender.core.data.network.parseReadiness
import com.digifender.adminifender.core.data.network.parseResultRequest
import com.digifender.adminifender.core.data.network.wireJson
import com.digifender.adminifender.core.model.Readiness
import com.digifender.adminifender.core.model.Thermal
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpDeviceWorkApiTest {
    private val server = MockWebServer()
    private var signedIn = true
    private lateinit var api: HttpDeviceWorkApi
    private val ready = Readiness(foreground = false, charging = true, batteryPercent = 80, thermal = Thermal.NONE, modelId = "gemma-3-1b-it-int4", modelManifestHash = "a".repeat(64))
    private val attempt = uuid(1)
    private val lease = uuid(5)

    @Before
    fun start() {
        server.start()
        val tokens = object : AccessTokens {
            override suspend fun current() = if (signedIn) "access-token" else null

            override suspend fun rejected(token: String) = Unit
        }
        api = HttpDeviceWorkApi(OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build(), server.url("/"), tokens)
    }

    @After
    fun stop() = server.close()

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code).addHeader("content-type", "application/json").body(body).build()

    private fun data(contract: String, name: String) = """{"data":${contractFile("fixtures/v1/$contract/valid/$name.json").trim()}}"""

    private fun error(code: String) = """{"error":{"code":"$code","message":"m","retryable":false,"details":null}}"""

    @Test
    fun claimingSendsTheReadinessUnderTheKeyAndReadsTheLeasedTask() = runBlocking {
        server.enqueue(json(200, data("device-work-claim", "triage")))
        val key = uuid(77)
        val claimed = api.claim(key, ready) as CallResult.Ok
        assertEquals("triage", claimed.value.work!!.task)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/device-work/claim", request.url.encodedPath)
        assertEquals(key, request.headers["idempotency-key"])
        assertEquals("Bearer access-token", request.headers["authorization"])
        assertEquals(ready, parseReadiness(wireJson.parseToJsonElement(request.body!!.utf8())))
    }

    @Test
    fun noWorkIsAnAnswerAndNotAnError() = runBlocking {
        server.enqueue(json(200, data("device-work-claim", "not-ready")))
        val claimed = api.claim(uuid(78), ready) as CallResult.Ok
        assertEquals("not_ready", claimed.value.reason)
    }

    @Test
    fun aResultIsSentUnderTheSameKeyEachTimeAndTheAnswerIsChecked() = runBlocking {
        server.enqueue(json(200, data("device-work-result-answer", "succeeded").replace(uuid(1), attempt)))
        val result = WorkResult(lease, "a".repeat(64), 2, "{\"concerns\":[]}", "b".repeat(64))
        val submitted = api.submit(attempt, uuid(79), result) as CallResult.Ok
        assertEquals("succeeded", submitted.value.state)
        val request = server.takeRequest()
        assertEquals("/api/v1/device-work/$attempt/result", request.url.encodedPath)
        assertEquals(uuid(79), request.headers["idempotency-key"])
        assertEquals(result, parseResultRequest(wireJson.parseToJsonElement(request.body!!.utf8())))

        // An answer about another task is not an answer to this one: whether it was stored is unknown.
        server.enqueue(json(200, """{"data":{"attemptId":"${uuid(2)}","state":"succeeded"}}"""))
        assertEquals(CallResult.NoAnswer, api.submit(attempt, uuid(79), result))
    }

    @Test
    fun heartbeatsAndDeclinesAreSentUnderTheLeaseAndTheirAnswersAreChecked() = runBlocking {
        server.enqueue(json(200, """{"data":{"attemptId":"$attempt","expiresAt":"2026-10-09T12:10:00.000Z"}}"""))
        assertEquals(CallResult.Ok(Unit), api.heartbeat(attempt, uuid(80), lease))
        val beat = server.takeRequest()
        assertEquals("/api/v1/device-work/$attempt/heartbeat", beat.url.encodedPath)
        assertTrue(beat.body!!.utf8().contains(lease))

        server.enqueue(json(200, """{"data":{"attemptId":"$attempt","state":"cancelled","reason":"input_too_large"}}"""))
        assertEquals(CallResult.Ok(Unit), api.decline(attempt, uuid(81), lease, "input_too_large"))
        val decline = server.takeRequest()
        assertEquals("/api/v1/device-work/$attempt/decline", decline.url.encodedPath)
        assertEquals(lease to "input_too_large", parseDeclineRequest(wireJson.parseToJsonElement(decline.body!!.utf8())))

        server.enqueue(json(200, """{"data":{"attemptId":"$attempt","state":"leased","reason":"input_too_large"}}"""))
        assertEquals(CallResult.NoAnswer, api.decline(attempt, uuid(82), lease, "input_too_large"))
    }

    @Test
    fun refusalsNoAnswerAndSignInAreToldApart() = runBlocking {
        server.enqueue(json(409, error("lease_lost")))
        assertEquals(CallResult.Refused("lease_lost"), api.heartbeat(attempt, uuid(83), lease))
        server.enqueue(json(401, error("unauthenticated")))
        assertEquals(CallResult.SessionExpired, api.claim(uuid(84), ready))
        server.enqueue(json(503, error("unavailable")))
        assertEquals(CallResult.NoAnswer, api.claim(uuid(85), ready))
        server.enqueue(json(429, error("rate_limited")))
        assertEquals(CallResult.NoAnswer, api.claim(uuid(86), ready))
        server.enqueue(json(200, """{"data":{"work":{"attemptId":"nope"}}}"""))
        assertEquals(CallResult.NoAnswer, api.claim(uuid(87), ready))
        // Nothing is sent for a request that cannot be valid.
        val before = server.requestCount
        assertEquals(CallResult.Refused("invalid_request"), api.claim("not-a-key", ready))
        assertEquals(CallResult.Refused("invalid_request"), api.heartbeat("../x", uuid(88), lease))
        signedIn = false
        assertEquals(CallResult.SessionExpired, api.claim(uuid(89), ready))
        assertEquals(before, server.requestCount)
    }
}
