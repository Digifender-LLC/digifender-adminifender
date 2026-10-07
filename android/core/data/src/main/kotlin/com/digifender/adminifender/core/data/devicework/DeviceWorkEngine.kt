package com.digifender.adminifender.core.data.devicework

import com.digifender.adminifender.core.model.DeviceConditions
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** Only a hash-verified artifact loaded by the selected runtime may be advertised. */
data class LoadedDeviceModel(val modelId: String, val manifestHash: String) {
    init { require(modelId.matches(Regex("^[A-Za-z0-9._:/-]{1,100}$")) && DEVICE_HASH.matches(manifestHash)) }
}

sealed interface LocalRuntimeStatus {
    /** Both triage/chat input retrieval, the artifact tokenizer and CPU runtime must be usable. */
    data class Ready(val model: LoadedDeviceModel) : LocalRuntimeStatus
    data class Unavailable(val reason: String) : LocalRuntimeStatus
}

sealed interface LocalGeneration {
    /** Counts must come from the artifact tokenizer, including prompt/history/evidence. */
    data class Output(val json: String, val inputTokens: Int, val outputTokens: Int) : LocalGeneration
    data class Deferred(val reason: String) : LocalGeneration
}

interface LocalInferenceRuntime {
    fun status(): LocalRuntimeStatus
    /** Must cooperate with coroutine cancellation and never send source bytes to another service. */
    suspend fun generate(work: InferenceWork): LocalGeneration
}

/** No unverified runtime version or fabricated model artifact is shipped in this slice. */
class UnavailableLocalInferenceRuntime : LocalInferenceRuntime {
    override fun status() = LocalRuntimeStatus.Unavailable("model_not_configured")
    override suspend fun generate(work: InferenceWork) = LocalGeneration.Deferred("model_not_configured")
}

fun interface DeviceConditionsSource { fun current(): DeviceConditions }

sealed interface DeviceWorkState {
    data object Idle : DeviceWorkState
    data class Deferred(val reason: String) : DeviceWorkState
    data object SignInRequired : DeviceWorkState
    data class Running(val attemptId: String) : DeviceWorkState
    data class Finished(val attemptId: String, val state: String) : DeviceWorkState
    /** Server acceptance is unknown; this is never rendered as successful generation. */
    data class SubmissionUnknown(val attemptId: String) : DeviceWorkState
    data class Unavailable(val reason: String) : DeviceWorkState
}

/**
 * One process-wide worker and one server lease. Every pass refreshes readiness, including unavailable models.
 * A claim lost in transit is left to expire; the server's device busy check prevents a second live task.
 * No model text or lease credentials are persisted or logged by this orchestration layer.
 */
class DeviceWorkEngine(
    private val api: DeviceWorkApi,
    private val conditions: DeviceConditionsSource,
    private val runtime: LocalInferenceRuntime,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<DeviceWorkState>(DeviceWorkState.Idle)
    val state: StateFlow<DeviceWorkState> = mutableState.asStateFlow()

    suspend fun runOnce(interactive: Boolean = false): DeviceWorkState {
        if (!mutex.tryLock()) return DeviceWorkState.Deferred("busy")
        var submittingAttemptId: String? = null
        try {
            val status = runtime.status()
            val model = (status as? LocalRuntimeStatus.Ready)?.model
            val snapshot = conditions.current()
            val readiness = ReadinessRequest.from(snapshot, model)
            when (val reported = api.readiness(readiness, newKey())) {
                is DeviceCall.Ok -> Unit
                else -> return update(failure(reported))
            }
            if (status is LocalRuntimeStatus.Unavailable) return update(DeviceWorkState.Deferred(status.reason))
            snapshot.deferral(interactive, model != null)?.let { return update(DeviceWorkState.Deferred(it)) }
            val claim = when (val result = api.claim(readiness, newKey())) {
                is DeviceCall.Ok -> result.value
                else -> return update(failure(result))
            }
            val work = claim.work ?: return update(DeviceWorkState.Deferred(claim.reason ?: "no_work"))
            if (!valid(work, model!!)) return update(DeviceWorkState.Deferred("invalid_claim"))
            update(DeviceWorkState.Running(work.attemptId))
            // The server may hand a charging foreground phone a background triage task.
            return update(runLeased(work, model, interactive && work.task == "chat") { submittingAttemptId = work.attemptId })
        } catch (cancelled: CancellationException) {
            update(submittingAttemptId?.let { DeviceWorkState.SubmissionUnknown(it) } ?: DeviceWorkState.Deferred("cancelled"))
            throw cancelled
        } finally {
            mutex.unlock()
        }
    }

    private fun valid(work: InferenceWork, model: LoadedDeviceModel): Boolean =
        DEVICE_UUID.matches(work.attemptId) && DEVICE_UUID.matches(work.jobId) && DEVICE_UUID.matches(work.leaseToken) &&
            DEVICE_HASH.matches(work.inputDigest) && work.policyRevision >= 0 && work.modelId == model.modelId &&
            work.task in setOf("triage", "chat") && (work.task != "triage" || conditions.current().deferral(false, true) == null) &&
            work.chunkIds.size <= (if (work.task == "triage") 64 else 4) && work.chunkIds.all { it.length in 1..512 } &&
            (work.task != "triage" || (work.sourceId != null && DEVICE_UUID.matches(work.sourceId) && (work.sourceRevision ?: -1) >= 0)) &&
            (work.task != "chat" || (work.turnId != null && DEVICE_UUID.matches(work.turnId))) &&
            expiry(work.expiresAt)?.let { it > clock() && it <= clock() + LEASE_MS } == true

    private suspend fun runLeased(work: InferenceWork, model: LoadedDeviceModel, interactive: Boolean, submissionStarted: () -> Unit): DeviceWorkState {
        var submitting = false
        return try {
        var expiresAt = expiry(work.expiresAt)!!
        ensureReady(model, interactive, expiresAt)
        val generated = coroutineScope {
            val generation = async { runtime.generate(work) }
            val heartbeat = launch {
                while (true) {
                    delay(HEARTBEAT_MS)
                    ensureReady(model, interactive, expiresAt)
                    when (val renewed = api.heartbeat(work.attemptId, HeartbeatRequest(work.leaseToken), newKey())) {
                        is DeviceCall.Ok -> {
                            val next = expiry(renewed.value.expiresAt)
                            if (renewed.value.attemptId != work.attemptId || next == null || next <= clock() || next > clock() + LEASE_MS) throw LeaseStopped("invalid_heartbeat")
                            expiresAt = next
                        }
                        DeviceCall.SessionExpired -> throw LeaseStopped("sign_in_required")
                        is DeviceCall.Rejected -> throw LeaseStopped(renewed.code)
                        is DeviceCall.Unavailable -> throw LeaseStopped("heartbeat_unavailable")
                    }
                }
            }
            try { generation.await() } finally { heartbeat.cancel() }
        }
        ensureReady(model, interactive, expiresAt)
        if (generated is LocalGeneration.Deferred) return DeviceWorkState.Deferred(generated.reason)
        generated as LocalGeneration.Output
        if (generated.inputTokens !in 1..1536 || generated.outputTokens !in 1..256 || generated.json.length !in 1..65536) return DeviceWorkState.Deferred("model_output_limit")
        val body = InferenceSubmission(work.leaseToken, work.inputDigest, work.policyRevision, generated.json, model.manifestHash)
        submitting = true
        submissionStarted()
        when (val submitted = api.submit(work.attemptId, body, newKey())) {
            is DeviceCall.Ok -> if (submitted.value.attemptId == work.attemptId && submitted.value.state in setOf("succeeded", "discarded", "invalid_output")) {
                DeviceWorkState.Finished(work.attemptId, submitted.value.state)
            } else DeviceWorkState.SubmissionUnknown(work.attemptId)
            is DeviceCall.Unavailable -> DeviceWorkState.SubmissionUnknown(work.attemptId)
            else -> failure(submitted)
        }
    } catch (stopped: LeaseStopped) {
        if (stopped.reason == "sign_in_required") DeviceWorkState.SignInRequired else DeviceWorkState.Deferred(stopped.reason)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        if (submitting) DeviceWorkState.SubmissionUnknown(work.attemptId) else DeviceWorkState.Unavailable("runtime_failed")
    }
    }

    private fun ensureReady(model: LoadedDeviceModel, interactive: Boolean, expiresAt: Long) {
        if (clock() >= expiresAt) throw LeaseStopped("lease_expired")
        if ((runtime.status() as? LocalRuntimeStatus.Ready)?.model != model) throw LeaseStopped("model_changed")
        conditions.current().deferral(interactive, true)?.let { throw LeaseStopped(it) }
    }

    private fun update(value: DeviceWorkState): DeviceWorkState = value.also { mutableState.value = it }
    private fun failure(result: DeviceCall<*>) = when (result) {
        DeviceCall.SessionExpired -> DeviceWorkState.SignInRequired
        is DeviceCall.Rejected -> DeviceWorkState.Deferred(result.code)
        is DeviceCall.Unavailable -> DeviceWorkState.Unavailable(result.code)
        is DeviceCall.Ok -> DeviceWorkState.Unavailable("invalid_response")
    }
    private class LeaseStopped(val reason: String) : RuntimeException()
    private companion object {
        const val HEARTBEAT_MS = 60_000L
        const val LEASE_MS = 5 * 60_000L
        fun expiry(value: String): Long? = try { Instant.parse(value).toEpochMilli() } catch (_: Exception) { null }
    }
}
