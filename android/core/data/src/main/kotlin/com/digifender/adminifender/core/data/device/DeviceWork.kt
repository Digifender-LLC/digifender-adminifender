package com.digifender.adminifender.core.data.device

import com.digifender.adminifender.core.data.network.CallResult
import com.digifender.adminifender.core.model.Readiness
import com.digifender.adminifender.core.model.WorkKind
import com.digifender.adminifender.core.model.admits
import com.digifender.adminifender.core.model.mustStop
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
data class MessageDto(val role: String, val content: String)

/** One leased task (lld.md#triage); the messages are exactly what the cloud adapter would send. */
@Serializable
data class DeviceWorkDto(
    val attemptId: String,
    val jobId: String,
    val task: String,
    val policyRevision: Long,
    val inputDigest: String,
    val chunkIds: List<String> = emptyList(),
    val messages: List<MessageDto>,
    val repair: Boolean = false,
    val leaseToken: String,
    val expiresAt: String,
    val sourceId: String? = null,
    val turnId: String? = null,
)

@Serializable
data class ClaimDto(val work: DeviceWorkDto? = null, val reason: String? = null)

@Serializable
data class SubmitDto(val attemptId: String, val state: String, val errors: List<String> = emptyList(), val repairQueued: Boolean = false)

data class WorkResult(val leaseToken: String, val inputDigest: String, val policyRevision: Long, val outputJson: String, val modelManifestHash: String)

interface DeviceWorkApi {
    suspend fun claim(idempotencyKey: String, readiness: Readiness): CallResult<ClaimDto>

    suspend fun heartbeat(attemptId: String, idempotencyKey: String, leaseToken: String): CallResult<Unit>

    suspend fun submit(attemptId: String, idempotencyKey: String, result: WorkResult): CallResult<SubmitDto>

    suspend fun decline(attemptId: String, idempotencyKey: String, leaseToken: String, reason: String): CallResult<Unit>
}

/** A loaded, verified local model (lld.md#triage `LocalInferenceAdapter`). */
interface LocalModel {
    val manifest: ModelManifest

    val manifestHash: String

    /** Tokens for [messages] with the artifact's own tokenizer and chat template. */
    suspend fun countTokens(messages: List<MessageDto>): Int

    suspend fun generate(messages: List<MessageDto>, maxOutputTokens: Int): String
}

sealed interface RunOutcome {
    data object NotReady : RunOutcome

    data class NoWork(val reason: String?) : RunOutcome

    data class Submitted(val state: String) : RunOutcome

    data object Declined : RunOutcome

    /** The phone got too hot or too low on battery; the lease is left to expire and the work is handed out again. */
    data object Stopped : RunOutcome

    /** The server refused the result (lease lost, input or policy changed); nothing is retried under it. */
    data class Refused(val code: String) : RunOutcome

    data object NoAnswer : RunOutcome

    data object SignInRequired : RunOutcome
}

/**
 * Runs one local task at a time: claim, heartbeat while generating, submit under the lease. A result with no
 * answer is resent with the same key, which the server deduplicates. Nothing here reports success the server did
 * not confirm.
 */
class DeviceWorkRunner(
    private val api: DeviceWorkApi,
    private val model: suspend () -> LocalModel?,
    private val readiness: (LocalModel?) -> Readiness,
    private val heartbeatMs: Long = HEARTBEAT_MS,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) {
    private val oneAtATime = Mutex()

    suspend fun runOnce(kind: WorkKind): RunOutcome = oneAtATime.withLock {
        val loaded = model() ?: return RunOutcome.NotReady
        val ready = readiness(loaded)
        if (!ready.admits(kind)) return RunOutcome.NotReady
        val work = when (val c = api.claim(newKey(), ready)) {
            is CallResult.Ok -> c.value.work ?: return RunOutcome.NoWork(c.value.reason)
            CallResult.SessionExpired -> return RunOutcome.SignInRequired
            is CallResult.Refused -> return RunOutcome.Refused(c.code)
            CallResult.NoAnswer -> return RunOutcome.NoAnswer
        }
        // The artifact's tokenizer decides, never a character count (tdd.md#version-freeze-and-model-manifest).
        if (loaded.countTokens(work.messages) > loaded.manifest.appliedInputLimit) {
            return when (api.decline(work.attemptId, newKey(), work.leaseToken, "input_too_large")) {
                is CallResult.Ok -> RunOutcome.Declined
                CallResult.SessionExpired -> RunOutcome.SignInRequired
                is CallResult.Refused -> RunOutcome.Refused("lease_lost")
                CallResult.NoAnswer -> RunOutcome.NoAnswer
            }
        }
        val output = generateWithLease(work, loaded) ?: return RunOutcome.Stopped
        submit(work, loaded, output)
    }

    /** Generates while renewing the lease; returns null when the phone must stop or the lease was lost. */
    private suspend fun generateWithLease(work: DeviceWorkDto, loaded: LocalModel): String? = coroutineScope {
        var stopped = false
        val generation = async { loaded.generate(work.messages, loaded.manifest.appliedOutputLimit) }
        val keeper = launch {
            while (true) {
                delay(heartbeatMs)
                if (readiness(loaded).mustStop() || api.heartbeat(work.attemptId, newKey(), work.leaseToken) is CallResult.Refused) {
                    stopped = true
                    generation.cancel()
                    return@launch
                }
            }
        }
        try {
            generation.await()
        } catch (e: CancellationException) {
            // Only this runner's own stop is absorbed; cancellation from outside still propagates.
            if (stopped) null else throw e
        } finally {
            keeper.cancel()
        }
    }

    private suspend fun submit(work: DeviceWorkDto, loaded: LocalModel, output: String): RunOutcome {
        val key = newKey()
        val result = WorkResult(work.leaseToken, work.inputDigest, work.policyRevision, output, loaded.manifestHash)
        repeat(SUBMIT_ATTEMPTS) {
            when (val r = api.submit(work.attemptId, key, result)) {
                is CallResult.Ok -> return RunOutcome.Submitted(r.value.state)
                is CallResult.Refused -> return RunOutcome.Refused(r.code)
                CallResult.SessionExpired -> return RunOutcome.SignInRequired
                CallResult.NoAnswer -> Unit
            }
        }
        return RunOutcome.NoAnswer
    }

    companion object {
        const val HEARTBEAT_MS = 60_000L
        const val SUBMIT_ATTEMPTS = 3
    }
}
