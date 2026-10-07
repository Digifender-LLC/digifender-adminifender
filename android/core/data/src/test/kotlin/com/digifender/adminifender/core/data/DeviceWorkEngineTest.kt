package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.devicework.*
import com.digifender.adminifender.core.model.DeviceConditions
import com.digifender.adminifender.core.model.DeviceThermal
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeviceWorkEngineTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val model = LoadedDeviceModel("gemma-3-1b-it-int4", "a".repeat(64))
    private val baseTime = 1_800_000_000_000L
    private val work = InferenceWork(id, id, sourceId = id, sourceRevision = 1, policyRevision = 2, task = "triage", inputDigest = "b".repeat(64), chunkIds = listOf("chunk-1"), modelId = model.modelId, leaseToken = id, expiresAt = Instant.ofEpochMilli(baseTime + 300_000).toString())
    private var conditions = DeviceConditions(false, true, 70, DeviceThermal.NONE)

    private inner class Api : DeviceWorkApi {
        val reports = mutableListOf<ReadinessRequest>()
        var claims = 0
        var submissions = 0
        var heartbeats = 0
        var submitDelay = 0L
        var failSubmit = false
        var onClaim: () -> Unit = {}
        var response = work
        var submitResult: DeviceCall<InferenceReceipt> = DeviceCall.Ok(InferenceReceipt(id, "succeeded"))
        var heartbeatResult: DeviceCall<HeartbeatResponse> = DeviceCall.Rejected("lease_lost")
        override suspend fun readiness(body: ReadinessRequest, key: String): DeviceCall<ReadinessResponse> {
            reports += body
            return DeviceCall.Ok(ReadinessResponse(body.modelId != null))
        }
        override suspend fun claim(body: ReadinessRequest, key: String): DeviceCall<ClaimResponse> {
            claims++
            onClaim()
            return DeviceCall.Ok(ClaimResponse(response))
        }
        override suspend fun heartbeat(attemptId: String, body: HeartbeatRequest, key: String): DeviceCall<HeartbeatResponse> {
            heartbeats++
            return heartbeatResult
        }
        override suspend fun submit(attemptId: String, body: InferenceSubmission, key: String): DeviceCall<InferenceReceipt> {
            assertEquals(work.leaseToken, body.leaseToken)
            assertEquals(work.inputDigest, body.inputDigest)
            assertEquals(model.manifestHash, body.modelManifestHash)
            assertEquals(work.policyRevision, body.policyRevision)
            submissions++
            if (failSubmit) error("synthetic transport failure after send")
            delay(submitDelay)
            return submitResult
        }
    }

    private fun runtime(generate: suspend () -> LocalGeneration = { LocalGeneration.Output("{}", 120, 12) }) = object : LocalInferenceRuntime {
        override fun status() = LocalRuntimeStatus.Ready(model)
        override suspend fun generate(work: InferenceWork) = generate()
    }

    @Test
    fun unconfiguredModelReportsUnavailableWithoutTakingALease() = runTest {
        val api = Api()
        val engine = DeviceWorkEngine(api, { conditions }, UnavailableLocalInferenceRuntime(), { baseTime })
        assertEquals(DeviceWorkState.Deferred("model_not_configured"), engine.runOnce())
        assertNull(api.reports.single().modelId)
        assertNull(api.reports.single().modelManifestHash)
        assertEquals(0, api.claims)
    }

    @Test
    fun unknownOsTelemetryCannotAdvertiseReadyOrClaim() = runTest {
        conditions = conditions.copy(batteryPercent = null)
        val api = Api()
        val engine = DeviceWorkEngine(api, { conditions }, runtime(), { baseTime })
        assertEquals(DeviceWorkState.Deferred("telemetry_unavailable"), engine.runOnce())
        assertNull(api.reports.single().modelId)
        assertEquals(0, api.claims)
    }

    @Test
    fun submitsOnlyRealBoundedOutputWithTheClaimedFence() = runTest {
        val api = Api()
        assertEquals(DeviceWorkState.Finished(id, "succeeded"), DeviceWorkEngine(api, { conditions }, runtime(), { baseTime }).runOnce())
        assertEquals(1, api.submissions)
        api.submitResult = DeviceCall.Unavailable("network")
        assertEquals(DeviceWorkState.SubmissionUnknown(id), DeviceWorkEngine(api, { conditions }, runtime(), { baseTime }).runOnce())
    }

    @Test
    fun outputBeyondActualTokenizerLimitsIsNotSubmitted() = runTest {
        val api = Api()
        val engine = DeviceWorkEngine(api, { conditions }, runtime { LocalGeneration.Output("{}", 1537, 12) }, { baseTime })
        assertEquals(DeviceWorkState.Deferred("model_output_limit"), engine.runOnce())
        assertEquals(0, api.submissions)
    }

    @Test
    fun acceptsBackendTriageChunkBoundAndDefersTokenPackingToTheRuntime() = runTest {
        val api = Api().apply { response = work.copy(chunkIds = List(64) { "chunk-$it" }) }
        val engine = DeviceWorkEngine(api, { conditions }, runtime { LocalGeneration.Deferred("input_token_limit") }, { baseTime })
        assertEquals(DeviceWorkState.Deferred("input_token_limit"), engine.runOnce())
        assertEquals(0, api.submissions)
        api.response = work.copy(chunkIds = List(65) { "chunk-$it" })
        assertEquals(DeviceWorkState.Deferred("invalid_claim"), engine.runOnce())
    }

    @Test
    fun cancellationAfterSubmissionStartsPreservesUnknownServerAcceptance() = runTest {
        val api = Api().apply { submitDelay = 120_000 }
        val engine = DeviceWorkEngine(api, { conditions }, runtime(), { baseTime })
        val task = async { engine.runOnce() }
        testScheduler.runCurrent()
        assertEquals(1, api.submissions)
        task.cancelAndJoin()
        assertEquals(DeviceWorkState.SubmissionUnknown(id), engine.state.value)
        assertEquals(1, api.submissions)
    }

    @Test
    fun unexpectedTransportExceptionAfterSendIsStillUnknown() = runTest {
        val api = Api().apply { failSubmit = true }
        assertEquals(DeviceWorkState.SubmissionUnknown(id), DeviceWorkEngine(api, { conditions }, runtime(), { baseTime }).runOnce())
        assertEquals(1, api.submissions)
    }

    @Test
    fun chatDoesNotStartIfConditionsChangeDuringClaim() = runTest {
        conditions = conditions.copy(foreground = true)
        val api = Api().apply {
            response = work.copy(task = "chat", turnId = id)
            onClaim = { conditions = conditions.copy(thermal = DeviceThermal.SEVERE) }
        }
        var generated = false
        val engine = DeviceWorkEngine(api, { conditions }, runtime { generated = true; LocalGeneration.Output("{}", 120, 12) }, { baseTime })
        assertEquals(DeviceWorkState.Deferred("thermal"), engine.runOnce(interactive = true))
        assertFalse(generated)
    }

    @Test
    fun lostHeartbeatCancelsGenerationAndCannotPublishALateResult() = runTest {
        val api = Api()
        var cancelled = false
        val engine = DeviceWorkEngine(api, { conditions }, runtime {
            try { delay(120_000); LocalGeneration.Output("{}", 120, 12) } finally { cancelled = true }
        }, { baseTime + testScheduler.currentTime })
        assertEquals(DeviceWorkState.Deferred("lease_lost"), engine.runOnce())
        assertTrue(cancelled)
        assertEquals(1, api.heartbeats)
        assertEquals(0, api.submissions)
    }

    @Test
    fun thermalChangePreventsResultEvenBeforeFirstHeartbeat() = runTest {
        val api = Api()
        val engine = DeviceWorkEngine(api, { conditions }, runtime {
            conditions = conditions.copy(thermal = DeviceThermal.SEVERE)
            LocalGeneration.Output("{}", 120, 12)
        }, { baseTime })
        assertEquals(DeviceWorkState.Deferred("thermal"), engine.runOnce())
        assertEquals(0, api.submissions)
    }

    @Test
    fun onlyOneRuntimeCanRunPerEngineAndExpiredClaimsAreRefused() = runTest {
        val api = Api()
        val engine = DeviceWorkEngine(api, { conditions }, runtime { delay(10); LocalGeneration.Output("{}", 120, 12) }, { baseTime })
        val first = async { engine.runOnce() }
        testScheduler.runCurrent()
        assertEquals(DeviceWorkState.Deferred("busy"), engine.runOnce())
        assertEquals(DeviceWorkState.Finished(id, "succeeded"), first.await())
        api.response = work.copy(expiresAt = Instant.ofEpochMilli(baseTime).toString())
        assertEquals(DeviceWorkState.Deferred("invalid_claim"), engine.runOnce())
        assertEquals(1, api.submissions)
    }
}
