package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.AccessTokens
import com.digifender.adminifender.core.data.network.HttpArchiveTransferApi
import com.digifender.adminifender.core.data.network.TransferResult
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

private const val TRANSFER_ID = "00000000-0000-4000-9000-000000000021"
private const val TRANSFER_KEY = "00000000-0000-4000-9000-000000000022"

class HttpArchiveTransferApiTest {
    private val server = MockWebServer()
    private var rejected = false
    private lateinit var api: HttpArchiveTransferApi
    private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Before fun start() {
        server.start()
        api = HttpArchiveTransferApi(OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build(), server.url("/"), object : AccessTokens {
            override suspend fun current() = "synthetic-bearer"
            override suspend fun rejected(token: String) { rejected = true }
        })
    }

    @After fun stop() = server.close()

    private fun body(status: Int, json: String) = MockResponse.Builder().code(status).addHeader("content-type", "application/json").body(json).build()
    private fun content(text: String, hash: String = digest(text)) = MockResponse.Builder().code(200)
        .addHeader("content-disposition", "attachment; filename=../../malicious.html")
        .addHeader("x-content-type-options", "nosniff").addHeader("x-attachment-sha256", hash).body(text).build()

    @Test
    fun attachmentRequiresBearerExactHashAndSizeAndNeverUsesFilename() = runTest {
        server.enqueue(content("synthetic attachment"))
        val output = ByteArrayOutputStream()
        assertEquals(TransferResult.Ok(20L), api.attachment(TRANSFER_ID, digest("synthetic attachment"), 20, output))
        assertEquals("synthetic attachment", output.toString("UTF-8"))
        val request = server.takeRequest()
        assertEquals("/api/v1/attachments/$TRANSFER_ID/content", request.url.encodedPath)
        assertEquals("Bearer synthetic-bearer", request.headers["authorization"])
    }

    @Test
    fun corruptedBytesAndMismatchedHeaderFailIntegrity() = runTest {
        server.enqueue(content("corrupt", digest("correct")))
        assertEquals(TransferResult.Rejected("integrity_mismatch"), api.attachment(TRANSFER_ID, digest("correct"), 7, ByteArrayOutputStream()))
        server.enqueue(content("correct", digest("corrupt")))
        assertEquals(TransferResult.Rejected("integrity_mismatch"), api.attachment(TRANSFER_ID, digest("correct"), 7, ByteArrayOutputStream()))
    }

    @Test
    fun redirectsNeverReachTheirTarget() = runTest {
        server.enqueue(MockResponse.Builder().code(302).addHeader("location", server.url("/untrusted/mail-url")).build())
        assertTrue(api.attachment(TRANSFER_ID, digest("a"), 1, ByteArrayOutputStream()) is TransferResult.Rejected)
        assertEquals(1, server.requestCount)
        assertEquals("/api/v1/attachments/$TRANSFER_ID/content", server.takeRequest().url.encodedPath)
    }

    @Test
    fun unauthorizedContentMarksOnlyTheUsedTokenStale() = runTest {
        server.enqueue(body(401, """{"error":{"code":"unauthenticated"}}"""))
        assertEquals(TransferResult.SignIn, api.attachment(TRANSFER_ID, digest("a"), 1, ByteArrayOutputStream()))
        assertTrue(rejected)
    }

    @Test
    fun unsafeInlineAndOversizedAttachmentsAreNotDownloaded() = runTest {
        server.enqueue(MockResponse.Builder().code(200).addHeader("x-attachment-sha256", digest("a")).body("a").build())
        assertEquals(TransferResult.Rejected("unsafe_content"), api.attachment(TRANSFER_ID, digest("a"), 1, ByteArrayOutputStream()))
        assertEquals(TransferResult.Rejected("invalid_attachment"), api.attachment(TRANSFER_ID, digest("a"), Long.MAX_VALUE, ByteArrayOutputStream()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun exportCreationSendsExactlyEmptyBodyAndStableIdempotencyKey() = runTest {
        repeat(2) { server.enqueue(body(200, """{"data":{"id":"$TRANSFER_ID","state":"pending","snapshotSeq":42,"expiresAt":"2026-10-14T12:00:00Z"}}""")) }
        repeat(2) { assertTrue(api.createExport(TRANSFER_KEY) is TransferResult.Ok) }
        repeat(2) {
            val request = server.takeRequest()
            assertEquals("/api/v1/exports", request.url.encodedPath)
            assertEquals(TRANSFER_KEY, request.headers["idempotency-key"])
            assertTrue(Json.parseToJsonElement(request.body!!.utf8()).jsonObject.isEmpty())
        }
    }

    @Test
    fun reviewConfirmationUsesOnlyExactDigestAndReturnsConsumedOperationForReadback() = runTest {
        server.enqueue(body(409, """{"error":{"code":"review_consumed","details":{"operationId":"$TRANSFER_ID"}}}"""))
        assertEquals(TransferResult.Rejected("review_consumed", TRANSFER_ID), api.confirmDeletion(TRANSFER_ID, "a".repeat(64), TRANSFER_KEY))
        val request = server.takeRequest()
        assertEquals("/api/v1/reviews/$TRANSFER_ID/confirm", request.url.encodedPath)
        assertEquals(TRANSFER_KEY, request.headers["idempotency-key"])
        assertEquals(setOf("paramsDigest"), Json.parseToJsonElement(request.body!!.utf8()).jsonObject.keys)
    }

    @Test
    fun inFlightAndConflictingRequestKeysNeverEstablishAProvenFailure() = runTest {
        for (code in listOf("request_in_progress", "idempotency_conflict")) {
            server.enqueue(body(409, """{"error":{"code":"$code"}}"""))
            assertEquals(TransferResult.Unavailable(code), api.confirmDeletion(TRANSFER_ID, "a".repeat(64), TRANSFER_KEY))
        }
    }

    @Test
    fun expiredExportIsNotAnExpiredSyncCursorAndServerFailuresRemainUnknown() = runTest {
        server.enqueue(body(410, """{"error":{"code":"deleted"}}"""))
        assertEquals(TransferResult.Rejected("deleted"), api.exportContent(TRANSFER_ID, ByteArrayOutputStream()))
        server.enqueue(body(503, """{"error":{"code":"ledger_unavailable"}}"""))
        assertEquals(TransferResult.Unavailable("ledger_unavailable"), api.confirmDeletion(TRANSFER_ID, "a".repeat(64), TRANSFER_KEY))
        server.enqueue(body(200, """{"data":{}}"""))
        assertEquals(TransferResult.Unavailable("malformed_response"), api.createExport(TRANSFER_KEY))
    }
}
