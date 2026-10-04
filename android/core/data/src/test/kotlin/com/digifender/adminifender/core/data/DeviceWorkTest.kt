package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.device.ArtifactCheck
import com.digifender.adminifender.core.data.device.ClaimDto
import com.digifender.adminifender.core.data.device.DeviceWorkApi
import com.digifender.adminifender.core.data.device.DeviceWorkDto
import com.digifender.adminifender.core.data.device.DeviceWorkRunner
import com.digifender.adminifender.core.data.device.LocalModel
import com.digifender.adminifender.core.data.device.MessageDto
import com.digifender.adminifender.core.data.device.ModelManifest
import com.digifender.adminifender.core.data.device.RunOutcome
import com.digifender.adminifender.core.data.device.SubmitDto
import com.digifender.adminifender.core.data.device.WorkResult
import com.digifender.adminifender.core.data.device.verifyArtifact
import com.digifender.adminifender.core.data.network.CallResult
import com.digifender.adminifender.core.model.Readiness
import com.digifender.adminifender.core.model.Thermal
import com.digifender.adminifender.core.model.WorkKind
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val ARTIFACT = "model-bytes".toByteArray()
private val MANIFEST = ModelManifest(
    "gemma-3-1b-it-int4", "https://example.invalid/model", MessageDigest.getInstance("SHA-256").digest(ARTIFACT).joinToString("") { "%02x".format(it) },
    ARTIFACT.size.toLong(), "https://example.invalid/license", "0.17.1", 2048, 1536, 256, "t".repeat(8),
)

class ModelManifestTest {
    @Test
    fun theArtifactMustMatchSizeAndHash() {
        assertEquals(ArtifactCheck.OK, verifyArtifact(ARTIFACT.inputStream(), MANIFEST))
        assertEquals(ArtifactCheck.WRONG_SIZE, verifyArtifact((ARTIFACT + 1).inputStream(), MANIFEST))
        assertEquals(ArtifactCheck.WRONG_SIZE, verifyArtifact(ARTIFACT.copyOf(4).inputStream(), MANIFEST))
        val swapped = ARTIFACT.copyOf().also { it[0] = 'M'.code.toByte() }
        assertEquals(ArtifactCheck.WRONG_HASH, verifyArtifact(swapped.inputStream(), MANIFEST))
    }

    @Test
    fun limitsNeverExceedTheLocalV1CapsAndBadManifestsAreRefused() {
        val generous = MANIFEST.copy(contextTokens = 8192, inputLimit = 6000, outputLimit = 1000)
        assertEquals(1536, generous.appliedInputLimit)
        assertEquals(256, generous.appliedOutputLimit)
        assertEquals(listOf("limits"), MANIFEST.copy(inputLimit = 2000).problems())
        assertNull(ModelManifest.parse("""{"modelId":"x"}"""))
        assertEquals(MANIFEST, ModelManifest.parse(kotlinx.serialization.json.Json.encodeToString(ModelManifest.serializer(), MANIFEST)))
        assertEquals(64, MANIFEST.hash().length)
    }
}

private class FakeWorkApi : DeviceWorkApi {
    val claims = ArrayDeque<CallResult<ClaimDto>>()
    val submits = ArrayDeque<CallResult<SubmitDto>>()
    val heartbeats = ArrayDeque<CallResult<Unit>>()
    val log = mutableListOf<String>()
    val submitKeys = mutableListOf<String>()
    var submitted: WorkResult? = null

    override suspend fun claim(idempotencyKey: String, readiness: Readiness) = claims.removeFirst().also { log += "claim" }

    override suspend fun heartbeat(attemptId: String, idempotencyKey: String, leaseToken: String) = (heartbeats.removeFirstOrNull() ?: CallResult.Ok(Unit)).also { log += "heartbeat" }

    override suspend fun submit(attemptId: String, idempotencyKey: String, result: WorkResult): CallResult<SubmitDto> {
        log += "submit"
        submitKeys += idempotencyKey
        submitted = result
        return submits.removeFirst()
    }

    override suspend fun decline(attemptId: String, idempotencyKey: String, leaseToken: String, reason: String): CallResult<Unit> {
        log += "decline:$reason"
        return CallResult.Ok(Unit)
    }
}

private class FakeModel(private val tokens: Int, private val gate: CompletableDeferred<Unit>? = null) : LocalModel {
    override val manifest = MANIFEST
    override val manifestHash = MANIFEST.hash()
    var asked: Int? = null

    override suspend fun countTokens(messages: List<MessageDto>) = tokens

    override suspend fun generate(messages: List<MessageDto>, maxOutputTokens: Int): String {
        asked = maxOutputTokens
        gate?.await()
        return """{"answer":"ok"}"""
    }
}

class DeviceWorkRunnerTest {
    private val api = FakeWorkApi()
    private var thermal = Thermal.NONE
    private val work = DeviceWorkDto("a1", "j1", "chat", 3, "d".repeat(64), listOf("c"), listOf(MessageDto("system", "s"), MessageDto("user", "u")), leaseToken = "lease", expiresAt = "2026-10-04T00:05:00Z")

    private fun runner(model: LocalModel?) =
        DeviceWorkRunner(api, { model }, { m -> Readiness(true, true, 80, thermal, m?.manifest?.modelId, m?.manifestHash) }, heartbeatMs = 10, newKey = { "k${api.log.size}" })

    @Test
    fun withoutAModelNothingIsClaimed() = runTest {
        assertEquals(RunOutcome.NotReady, runner(null).runOnce(WorkKind.INTERACTIVE))
        thermal = Thermal.SEVERE
        assertEquals(RunOutcome.NotReady, runner(FakeModel(10)).runOnce(WorkKind.INTERACTIVE))
        assertTrue(api.log.isEmpty())
    }

    @Test
    fun runsUnderTheLeaseAndSubmitsWithTheManifestHash() = runTest {
        api.claims += CallResult.Ok(ClaimDto(work))
        api.submits += CallResult.Ok(SubmitDto("a1", "succeeded"))
        val model = FakeModel(100)
        assertEquals(RunOutcome.Submitted("succeeded"), runner(model).runOnce(WorkKind.INTERACTIVE))
        val sent = api.submitted!!
        assertEquals("lease", sent.leaseToken)
        assertEquals(work.inputDigest, sent.inputDigest)
        assertEquals(3L, sent.policyRevision)
        assertEquals(MANIFEST.hash(), sent.modelManifestHash)
        assertEquals(256, model.asked)
    }

    @Test
    fun inputOverTheTokenLimitIsDeclinedNotGenerated() = runTest {
        api.claims += CallResult.Ok(ClaimDto(work))
        val model = FakeModel(1537)
        assertEquals(RunOutcome.Declined, runner(model).runOnce(WorkKind.BACKGROUND))
        assertEquals(listOf("claim", "decline:input_too_large"), api.log)
        assertNull(model.asked)
    }

    @Test
    fun anUnansweredSubmitIsResentWithTheSameKey() = runTest {
        api.claims += CallResult.Ok(ClaimDto(work))
        api.submits += CallResult.NoAnswer
        api.submits += CallResult.Ok(SubmitDto("a1", "succeeded"))
        assertEquals(RunOutcome.Submitted("succeeded"), runner(FakeModel(10)).runOnce(WorkKind.INTERACTIVE))
        assertEquals(2, api.submitKeys.size)
        assertEquals(api.submitKeys[0], api.submitKeys[1])
    }

    @Test
    fun aRefusedResultIsNotRetried() = runTest {
        api.claims += CallResult.Ok(ClaimDto(work))
        api.submits += CallResult.Refused("policy_changed")
        assertEquals(RunOutcome.Refused("policy_changed"), runner(FakeModel(10)).runOnce(WorkKind.INTERACTIVE))
        assertEquals(1, api.submitKeys.size)
    }

    @Test
    fun overheatingStopsGenerationAndSubmitsNothing() = runTest {
        api.claims += CallResult.Ok(ClaimDto(work))
        val never = CompletableDeferred<Unit>()
        val r = runner(FakeModel(10, never))
        thermal = Thermal.NONE
        val outcome = backgroundScope.async { r.runOnce(WorkKind.INTERACTIVE) }
        testScheduler.advanceTimeBy(15)
        thermal = Thermal.CRITICAL
        testScheduler.advanceTimeBy(20)
        assertEquals(RunOutcome.Stopped, outcome.await())
        assertTrue("submit" !in api.log)
    }

    @Test
    fun aLostLeaseStopsGeneration() = runTest {
        api.claims += CallResult.Ok(ClaimDto(work))
        api.heartbeats += CallResult.Refused("lease_lost")
        assertEquals(RunOutcome.Stopped, runner(FakeModel(10, CompletableDeferred())).runOnce(WorkKind.INTERACTIVE))
        assertTrue("submit" !in api.log)
    }

    @Test
    fun noWorkCarriesTheServerReason() = runTest {
        api.claims += CallResult.Ok(ClaimDto(null, "busy"))
        assertEquals(RunOutcome.NoWork("busy"), runner(FakeModel(10)).runOnce(WorkKind.BACKGROUND))
    }
}
