package com.digifender.adminifender.core.data.health

import com.digifender.adminifender.core.data.network.AccessTokens
import com.digifender.adminifender.core.data.network.ReadResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpHealthApiTest {
    private val server = MockWebServer()
    private val rejected = mutableListOf<String>()
    private var token: String? = "synthetic-access-token"
    private lateinit var api: HttpHealthApi
    private val body = """{"data":{"generatedAt":"2026-10-07T10:00:00Z"}}"""

    @Before fun start() {
        server.start()
        api = HttpHealthApi(OkHttpClient(), server.url("/"), object : AccessTokens {
            override suspend fun current() = token
            override suspend fun rejected(token: String) { rejected += token }
        })
    }
    @After fun stop() = server.close()
    private fun response(code: Int, body: String) = MockResponse.Builder().code(code).body(body).build()

    @Test fun bothReadsUseAuthenticatedPrivateContracts() = runTest {
        server.enqueue(response(200, body)); server.enqueue(response(200, body))
        assertTrue(api.health() is ReadResult.Ok)
        assertTrue(api.diagnostics() is ReadResult.Ok)
        val health = server.takeRequest()
        val diagnostics = server.takeRequest()
        assertEquals("/api/v1/health/private", health.url.encodedPath)
        assertEquals("/api/v1/diagnostics", diagnostics.url.encodedPath)
        for (request in listOf(health, diagnostics)) {
            assertEquals("GET", request.method)
            assertEquals("Bearer synthetic-access-token", request.headers["authorization"])
        }
    }

    @Test fun signoutDoesNotRequestAndUnauthorizedInvalidatesExactToken() = runTest {
        token = null
        assertEquals(ReadResult.SessionExpired, api.health())
        assertEquals(0, server.requestCount)
        token = "synthetic-access-token"
        server.enqueue(response(401, "secret-synthetic-marker"))
        assertEquals(ReadResult.SessionExpired, api.diagnostics())
        assertEquals(listOf("synthetic-access-token"), rejected)
    }

    @Test fun errorsMalformedBodiesAndOversizeHaveOnlyFixedFailure() = runTest {
        for ((code, text) in listOf(503 to "secret-synthetic-marker", 200 to "not-json", 200 to "{}", 200 to "x".repeat(1024 * 1024 + 1))) {
            server.enqueue(response(code, text))
            assertEquals(ReadResult.Unavailable("health_unavailable"), api.health())
        }
    }

    @Test fun redirectsCannotForwardCredentials() = runTest {
        server.enqueue(MockResponse.Builder().code(302).addHeader("location", server.url("/other").toString()).build())
        assertEquals(ReadResult.Unavailable("health_unavailable"), api.health())
        assertEquals(1, server.requestCount)
    }

    @Test fun delayedResponseBodyIsReadAndClosedOffCallerThread() = runTest {
        val threads = CopyOnWriteArrayList<String>()
        var closed = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val original = response.body
            val wrapped = object : ResponseBody() {
                private val source = object : ForwardingSource(original.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long { threads += Thread.currentThread().name; return super.read(sink, byteCount) }
                    override fun close() { closed = true; super.close() }
                }.buffer()
                override fun contentType() = original.contentType()
                override fun contentLength() = original.contentLength()
                override fun source(): BufferedSource = source
            }
            response.newBuilder().body(wrapped).build()
        }.build()
        server.enqueue(MockResponse.Builder().code(200).bodyDelay(50, TimeUnit.MILLISECONDS).body(body).build())
        val transport = HttpHealthApi(client, server.url("/"), AccessTokens { "synthetic-access-token" })
        Executors.newSingleThreadExecutor { Thread(it, "health-ui-caller") }.asCoroutineDispatcher().use { caller ->
            withContext(caller) { assertTrue(transport.health() is ReadResult.Ok) }
        }
        assertTrue(threads.isNotEmpty())
        assertTrue(threads.none { it == "health-ui-caller" })
        assertTrue(closed)
    }
}
