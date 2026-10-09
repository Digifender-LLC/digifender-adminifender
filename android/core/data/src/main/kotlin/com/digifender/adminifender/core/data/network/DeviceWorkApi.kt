package com.digifender.adminifender.core.data.network

import com.digifender.adminifender.core.data.device.ClaimDto
import com.digifender.adminifender.core.data.device.DeviceWorkApi
import com.digifender.adminifender.core.data.device.DeviceWorkDto
import com.digifender.adminifender.core.data.device.MessageDto
import com.digifender.adminifender.core.data.device.SubmitDto
import com.digifender.adminifender.core.data.device.WorkResult
import com.digifender.adminifender.core.model.Readiness
import com.digifender.adminifender.core.model.Thermal
import java.io.IOException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

// Local inference tasks (lld.md#triage). The shapes are the shared `device-work-*` contracts; every parser below is strict
// about them, so a changed Worker shows up as an answer the phone does not trust, never as a half-read task.
private const val MAX_MESSAGES = 64
private const val MAX_OUTPUT_CHARS = 65_536
private const val MAX_MESSAGE_CHARS = 200_000
private val TASKS = mapOf("triage" to "triage", "chat" to "chat")
private val ROLES = mapOf("system" to "system", "user" to "user", "assistant" to "assistant")
private val CLAIM_REASONS = mapOf("not_ready" to "not_ready", "busy" to "busy", "no_work" to "no_work")
private val THERMAL_BY_WIRE = Thermal.entries.associateBy { it.wire }
private val MODEL_ID = Regex("^[A-Za-z0-9._:/-]+$")
private val CHUNK_ID = Regex("^[A-Za-z0-9:_.-]+$")

/** `device-work-readiness.schema.json`: what the operating system says about the phone and the loaded model. */
fun readinessBody(readiness: Readiness): JsonObject = buildJsonObject {
    put("foreground", readiness.foreground)
    put("charging", readiness.charging)
    put("batteryPercent", readiness.batteryPercent)
    put("thermal", readiness.thermal.wire)
    put("modelId", readiness.modelId?.let { JsonPrimitive(it) } ?: JsonNull)
    put("modelManifestHash", readiness.modelManifestHash?.let { JsonPrimitive(it) } ?: JsonNull)
}

fun parseReadiness(element: JsonElement): Readiness {
    val path = "$"
    val body = element.asObject(path).exactly(path, setOf("foreground", "charging", "batteryPercent", "thermal", "modelId", "modelManifestHash"))
    val modelId = body.nullableText("modelId", path, MODEL_ID)?.also { if (it.isEmpty() || it.length > 100) throw WireException("$.modelId: wrong length") }
    val hash = if (body.field("modelManifestHash", path) is JsonNull) null else body.digest("modelManifestHash", path)
    val battery = body.int("batteryPercent", path, 0).also { if (it > 100) throw WireException("$.batteryPercent: above 100") }
    return Readiness(body.bool("foreground", path), body.bool("charging", path), battery, body.choice("thermal", path, THERMAL_BY_WIRE), modelId, hash)
}

/** `device-work-claim.schema.json`: one leased task or `work: null` with the reason. */
fun parseDeviceClaim(element: JsonElement): ClaimDto {
    val path = "$"
    val body = element.asObject(path)
    val workElement = body.field("work", path)
    if (workElement is JsonNull) {
        body.exactly(path, setOf("work", "reason"))
        return ClaimDto(null, body.choice("reason", path, CLAIM_REASONS))
    }
    body.exactly(path, setOf("work"))
    val p = "$.work"
    val work = workElement.asObject(p).exactly(
        p,
        setOf("attemptId", "jobId", "policyRevision", "task", "inputDigest", "chunkIds", "messages", "repair", "modelId", "leaseToken", "expiresAt"),
        setOf("sourceId", "sourceRevision", "turnId"),
    )
    val task = work.choice("task", p, TASKS)
    // A triage task names the email and its revision; a chat task names the turn; neither carries the other's fields.
    val hasSource = "sourceId" in work || "sourceRevision" in work
    if (task == "triage" && (!("sourceId" in work && "sourceRevision" in work) || "turnId" in work)) throw WireException("$p: a triage task names its email and revision only")
    if (task == "chat" && ("turnId" !in work || hasSource)) throw WireException("$p: a chat task names its turn only")
    val messages = work.array("messages", p).also { if (it.isEmpty() || it.size > MAX_MESSAGES) throw WireException("$p.messages: wrong length") }.mapIndexed { i, item ->
        val m = "$p.messages[$i]"
        val entry = item.asObject(m).exactly(m, setOf("role", "content"))
        MessageDto(entry.choice("role", m, ROLES), entry.text("content", m, max = MAX_MESSAGE_CHARS))
    }
    val chunkIds = work.array("chunkIds", p).also { if (it.size > MAX_MESSAGES) throw WireException("$p.chunkIds: too many") }.mapIndexed { i, item ->
        val primitive = item as? JsonPrimitive
        if (primitive == null || !primitive.isString || primitive.content.isEmpty() || primitive.content.length > 200 || !CHUNK_ID.matches(primitive.content)) throw WireException("$p.chunkIds[$i]: expected a chunk ID")
        primitive.content
    }
    return ClaimDto(
        DeviceWorkDto(
            attemptId = work.uuid("attemptId", p),
            jobId = work.uuid("jobId", p),
            task = task,
            policyRevision = work.long("policyRevision", p, 0),
            inputDigest = work.digest("inputDigest", p),
            chunkIds = chunkIds,
            messages = messages,
            repair = work.bool("repair", p),
            leaseToken = work.uuid("leaseToken", p),
            expiresAt = work.text("expiresAt", p).also { parseTimestamp(it, "$p.expiresAt") },
            sourceId = if ("sourceId" in work) work.uuid("sourceId", p) else null,
            sourceRevision = if ("sourceRevision" in work) work.int("sourceRevision", p, 1) else null,
            turnId = if ("turnId" in work) work.uuid("turnId", p) else null,
            modelId = work.nullableText("modelId", p),
        ),
        null,
    )
}

/** `device-work-result.schema.json`. */
fun resultBody(result: WorkResult): JsonObject = buildJsonObject {
    put("leaseToken", result.leaseToken)
    put("inputDigest", result.inputDigest)
    put("policyRevision", result.policyRevision)
    put("outputJson", result.outputJson)
    put("modelManifestHash", result.modelManifestHash)
}

fun parseResultRequest(element: JsonElement): WorkResult {
    val path = "$"
    val body = element.asObject(path).exactly(path, setOf("leaseToken", "inputDigest", "policyRevision", "outputJson", "modelManifestHash"))
    return WorkResult(
        body.uuid("leaseToken", path),
        body.digest("inputDigest", path),
        body.long("policyRevision", path, 0),
        body.text("outputJson", path, max = MAX_OUTPUT_CHARS),
        body.digest("modelManifestHash", path),
    )
}

/** `device-work-result-answer.schema.json`: stored, set aside, or refused as invalid (with the reasons). */
fun parseResultAnswer(element: JsonElement): SubmitDto {
    val path = "$"
    val body = element.asObject(path)
    val state = body.text("state", path)
    return when (state) {
        "succeeded", "discarded" -> {
            body.exactly(path, setOf("attemptId", "state"))
            SubmitDto(body.uuid("attemptId", path), state)
        }
        "invalid_output" -> {
            body.exactly(path, setOf("attemptId", "state", "errors", "repairQueued"))
            val errors = body.array("errors", path).mapIndexed { i, e ->
                val primitive = e as? JsonPrimitive
                if (primitive == null || !primitive.isString) throw WireException("$.errors[$i]: expected text")
                primitive.content
            }
            SubmitDto(body.uuid("attemptId", path), state, errors, body.bool("repairQueued", path))
        }
        else -> throw WireException("$.state: unknown value")
    }
}

/** `device-work-decline.schema.json`. */
fun declineBody(leaseToken: String, reason: String): JsonObject = buildJsonObject {
    put("leaseToken", leaseToken)
    put("reason", reason)
}

fun parseDeclineRequest(element: JsonElement): Pair<String, String> {
    val body = element.asObject("$").exactly("$", setOf("leaseToken", "reason"))
    val reason = body.text("reason", "$")
    if (reason != "input_too_large") throw WireException("$.reason: unknown value")
    return body.uuid("leaseToken", "$") to reason
}

/** `POST /device-work/claim`, `/{id}/heartbeat`, `/{id}/result` and `/{id}/decline`. */
class HttpDeviceWorkApi(client: OkHttpClient, baseUrl: HttpUrl, tokens: AccessTokens) : DeviceWorkApi {
    private val core = HttpCore(client, baseUrl, tokens)

    /** One POST under the caller's Idempotency-Key, so a repeat of the same request replays the stored answer. */
    private suspend fun <T> post(segments: Array<String>, key: String, body: JsonObject, parse: (JsonElement) -> T): CallResult<T> {
        if (!isUuid(key)) return CallResult.Refused("invalid_request")
        val request = Request.Builder().url(core.url(*segments)).header("idempotency-key", key).post(core.json(body.toString()))
        return try {
            val (call, response) = core.call(request) ?: return CallResult.SessionExpired
            response.use {
                val text = core.text(call, it)
                when {
                    it.isSuccessful -> CallResult.Ok(parse(core.data(text)))
                    it.code == 401 -> CallResult.SessionExpired
                    else -> core.error(text).let { e -> if (core.inconclusive(it.code, e.code)) CallResult.NoAnswer else CallResult.Refused(e.code) }
                }
            }
        } catch (_: IOException) {
            CallResult.NoAnswer
        } catch (_: IllegalArgumentException) {
            // An answer that is not the contract's shape says nothing about whether the request took effect.
            CallResult.NoAnswer
        }
    }

    override suspend fun claim(idempotencyKey: String, readiness: Readiness): CallResult<ClaimDto> = post(arrayOf("device-work", "claim"), idempotencyKey, readinessBody(readiness), ::parseDeviceClaim)

    override suspend fun heartbeat(attemptId: String, idempotencyKey: String, leaseToken: String): CallResult<Unit> {
        if (!isUuid(attemptId)) return CallResult.Refused("invalid_request")
        return post(arrayOf("device-work", attemptId, "heartbeat"), idempotencyKey, buildJsonObject { put("leaseToken", leaseToken) }) { data ->
            val body = data.asObject("$").exactly("$", setOf("attemptId", "expiresAt"))
            if (body.uuid("attemptId", "$") != attemptId) throw WireException("$.attemptId: another task")
            body.timestamp("expiresAt", "$")
            Unit
        }
    }

    override suspend fun submit(attemptId: String, idempotencyKey: String, result: WorkResult): CallResult<SubmitDto> {
        if (!isUuid(attemptId)) return CallResult.Refused("invalid_request")
        return post(arrayOf("device-work", attemptId, "result"), idempotencyKey, resultBody(result)) { data ->
            val answer = parseResultAnswer(data)
            if (answer.attemptId != attemptId) throw WireException("$.attemptId: another task")
            answer
        }
    }

    override suspend fun decline(attemptId: String, idempotencyKey: String, leaseToken: String, reason: String): CallResult<Unit> {
        if (!isUuid(attemptId)) return CallResult.Refused("invalid_request")
        return post(arrayOf("device-work", attemptId, "decline"), idempotencyKey, declineBody(leaseToken, reason)) { data ->
            val body = data.asObject("$").exactly("$", setOf("attemptId", "state", "reason"))
            if (body.uuid("attemptId", "$") != attemptId || body.text("state", "$") != "cancelled") throw WireException("$: not the cancellation asked for")
            Unit
        }
    }
}
