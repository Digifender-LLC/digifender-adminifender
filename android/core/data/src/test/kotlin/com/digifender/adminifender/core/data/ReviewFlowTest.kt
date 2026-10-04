package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.BriefingDto
import com.digifender.adminifender.core.data.network.CallResult
import com.digifender.adminifender.core.data.network.DevicesDto
import com.digifender.adminifender.core.data.network.ExportDto
import com.digifender.adminifender.core.data.network.OperationDto
import com.digifender.adminifender.core.data.network.OwnerApi
import com.digifender.adminifender.core.data.network.ReadResult
import com.digifender.adminifender.core.data.network.ReviewDto
import com.digifender.adminifender.core.data.network.StreamResult
import com.digifender.adminifender.core.data.repository.ConfirmOutcome
import com.digifender.adminifender.core.data.repository.ReviewFlow
import com.digifender.adminifender.core.data.repository.ReviewOutcome
import com.digifender.adminifender.core.data.repository.previewOrNull
import com.digifender.adminifender.core.data.session.PasskeyAuthenticator
import com.digifender.adminifender.core.data.session.RecentAuthOutcome
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeOwnerApi : OwnerApi {
    val confirms = ArrayDeque<CallResult<OperationDto>>()
    val confirmKeys = mutableListOf<String>()
    var reviewed: Pair<String, JsonObject>? = null

    override suspend fun briefing(): ReadResult<BriefingDto> = ReadResult.Unavailable("network")
    override suspend fun reviewBriefing(id: String, idempotencyKey: String): CallResult<Unit> = CallResult.Ok(Unit)
    override suspend fun devices(): ReadResult<DevicesDto> = ReadResult.Unavailable("network")
    override suspend fun createReview(idempotencyKey: String, effect: String, params: JsonObject): CallResult<ReviewDto> {
        reviewed = effect to params
        return CallResult.Ok(ReviewDto("r1", effect, "d".repeat(64), JsonObject(emptyMap()), "2026-10-04T00:10:00Z"))
    }
    override suspend fun confirmReview(id: String, idempotencyKey: String, paramsDigest: String): CallResult<OperationDto> {
        confirmKeys += idempotencyKey
        return confirms.removeFirst()
    }
    override suspend fun createExport(idempotencyKey: String): CallResult<ExportDto> = CallResult.NoAnswer
    override suspend fun export(id: String): ReadResult<ExportDto> = ReadResult.Unavailable("network")
    override suspend fun saveExport(id: String, out: OutputStream) = StreamResult.UNAVAILABLE
    override suspend fun saveAttachment(id: String, out: OutputStream, maxBytes: Long, sha256: String) = StreamResult.UNAVAILABLE
}

class ReviewFlowTest {
    private val api = FakeOwnerApi()
    private var passkey: RecentAuthOutcome = RecentAuthOutcome.Confirmed(1)
    private var prompts = 0
    private val flow = ReviewFlow(api, { prompts++; passkey })
    private val passkeys = PasskeyAuthenticator { error("not used by the fake") }

    private suspend fun review() = (flow.deleteSources(listOf("s1")) as ReviewOutcome.Ready).review

    @Test
    fun theServerBuildsThePreviewFromExactTargets() = runTest {
        review()
        assertEquals("archive.delete", api.reviewed!!.first)
        assertEquals("""{"sourceIds":["s1"]}""", api.reviewed!!.second.toString())
        flow.revokeDevice("d1")
        assertEquals(JsonPrimitive("d1"), api.reviewed!!.second["deviceId"])
    }

    @Test
    fun aMissingRecentPasskeyIsAskedOnceThenTheSameConfirmationIsSent() = runTest {
        val r = review()
        api.confirms += CallResult.Refused("recent_auth_required")
        api.confirms += CallResult.Ok(OperationDto("op", "succeeded"))
        assertEquals(ConfirmOutcome.Done("op"), flow.confirm(r, "key-1", passkeys))
        assertEquals(1, prompts)
        assertEquals(listOf("key-1", "key-1"), api.confirmKeys)
    }

    @Test
    fun aCancelledPasskeyChangesNothing() = runTest {
        val r = review()
        passkey = RecentAuthOutcome.Cancelled
        api.confirms += CallResult.Refused("recent_auth_required")
        assertEquals(ConfirmOutcome.PasskeyCancelled, flow.confirm(r, "k", passkeys))
        assertEquals(1, api.confirmKeys.size)
    }

    @Test
    fun changedTargetsOrExpiryMeanReviewingAgain() = runTest {
        val r = review()
        api.confirms += CallResult.Refused("stale_review")
        api.confirms += CallResult.Refused("review_expired")
        assertEquals(ConfirmOutcome.ReviewAgain, flow.confirm(r, "k", passkeys))
        assertEquals(ConfirmOutcome.ReviewAgain, flow.confirm(r, "k", passkeys))
    }

    @Test
    fun noAnswerIsReportedAsUnknownNeverAsDone() = runTest {
        val r = review()
        api.confirms += CallResult.NoAnswer
        assertEquals(ConfirmOutcome.Unknown, flow.confirm(r, "k", passkeys))
    }

    @Test
    fun thePreviewIsReadFromTheServersBoundSummaryAndUnknownShapesAreNotConfirmable() {
        fun dto(effect: String, preview: String) = ReviewDto("r", effect, "d", kotlinx.serialization.json.Json.parseToJsonElement(preview), "2026-10-04T00:10:00Z")
        val delete = dto("archive.delete", """{"sources":[{"id":"s","subject":"Invoice"},{"id":"t","subject":null}],"attachments":2,"concernsErased":["c"],"concernsDetached":[],"expectedCount":2}""").previewOrNull()!!
        assertEquals(listOf("Invoice", null), delete.subjects)
        assertEquals(2, delete.attachments)
        assertEquals(1, delete.concernsErased)
        val revoke = dto("device.revoke", """{"device":{"id":"d","name":"Pixel"},"current":true}""").previewOrNull()!!
        assertEquals("Pixel", revoke.deviceName)
        assertEquals(true, revoke.currentDevice)
        assertEquals(null, dto("archive.delete", """{"expectedCount":1}""").previewOrNull())
        assertEquals(null, dto("email.reply", "{}").previewOrNull())
    }
}
