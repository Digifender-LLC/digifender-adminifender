package com.digifender.adminifender.core.data.health

import com.digifender.adminifender.core.data.network.wireJson
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** A closed projection: unknown fields, free text, identifiers, model names and error strings are discarded. */
internal object HealthWire {
    private fun data(text: String): JsonObject = (wireJson.parseToJsonElement(text) as? JsonObject)?.obj("data") ?: throw IllegalArgumentException("health_envelope")
    private fun JsonObject.obj(key: String) = get(key) as? JsonObject
    private fun JsonObject.array(key: String): JsonArray? = (get(key) as? JsonArray)?.also { require(it.size <= 200) }
    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }
    private fun JsonObject.time(key: String): Long? = string(key)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }?.takeIf { it >= 0 }
    private fun JsonObject.status(key: String = "status"): HealthStatus = when (string(key)) {
        "ok" -> HealthStatus.OK
        "warning" -> HealthStatus.WARNING
        "stale" -> HealthStatus.STALE
        "unavailable" -> HealthStatus.UNAVAILABLE
        "unobserved" -> HealthStatus.UNOBSERVED
        null -> HealthStatus.UNOBSERVED
        else -> HealthStatus.UNAVAILABLE
    }
    private fun JsonObject.metric(label: HealthLabel, key: String, kind: Char = 'n'): HealthMetric {
        val value = when (kind) {
            't' -> time(key)?.let(HealthValue::Time)
            's' -> number(key)?.let(HealthValue::Seconds)
            'm' -> number(key)?.let(HealthValue::Milliseconds)
            'b' -> number(key)?.let(HealthValue::Bytes)
            'f' -> (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull?.let(HealthValue::Flag)
            'c' -> when (string(key)) {
                "auto" -> HealthChoice.AUTO
                "local_only" -> HealthChoice.LOCAL_ONLY
                "no_ai" -> HealthChoice.NO_AI
                "granted" -> HealthChoice.GRANTED
                "denied" -> HealthChoice.DENIED
                "unregistered" -> HealthChoice.UNREGISTERED
                "invalid" -> HealthChoice.INVALID
                else -> null
            }?.let(HealthValue::Choice)
            'a' -> array(key)?.size?.toLong()?.let(HealthValue::Number)
            else -> number(key)?.let(HealthValue::Number)
        }
        return HealthMetric(label, value ?: HealthValue.Unknown)
    }
    private val empty = JsonObject(emptyMap())
    private fun JsonObject?.panel(area: HealthArea, metrics: List<Triple<HealthLabel, String, Char>>, status: Boolean = true, ordinal: Int? = null) = HealthPanel(
        area, if (status) this?.status() ?: HealthStatus.UNOBSERVED else null,
        metrics.map { (label, key, kind) -> (this ?: empty).metric(label, key, kind) }, ordinal,
    )
    private fun m(label: HealthLabel, key: String, kind: Char = 'n') = Triple(label, key, kind)
    private val jobs = listOf(m(HealthLabel.OPEN, "open"), m(HealthLabel.OLDEST, "oldestAgeSeconds", 's'), m(HealthLabel.DEAD, "dead"), m(HealthLabel.FAILED_DAY, "failedLastDay"))

    fun health(text: String): HealthReport {
        val data = data(text)
        val generated = requireNotNull(data.time("generatedAt"))
        val inference = data.obj("inference")
        val panels = mutableListOf(
            data.obj("scheduler").panel(HealthArea.SCHEDULER, listOf(m(HealthLabel.LAST_PASS, "lastPassAt", 't'))),
            data.obj("capture").panel(HealthArea.CAPTURE, listOf(m(HealthLabel.LAST_RECEIPT, "lastReceiptAt", 't'), m(HealthLabel.WAITING, "waiting"), m(HealthLabel.OLDEST_WAIT, "oldestWaitingAgeSeconds", 's'), m(HealthLabel.QUARANTINED, "quarantined"))),
            data.obj("parsing").panel(HealthArea.PARSING, jobs + m(HealthLabel.PARSE_FAILED, "parseFailed")),
            data.obj("triage").panel(HealthArea.TRIAGE, jobs),
            data.obj("extraction").panel(HealthArea.EXTRACTION, jobs + listOf(m(HealthLabel.DEFERRED_DEVICE, "deferredForDevice"), m(HealthLabel.FAILED, "failed"), m(HealthLabel.UNSUPPORTED, "unsupported"))),
            inference.panel(HealthArea.INFERENCE, listOf(m(HealthLabel.CLOUD_BOUND, "cloudBound", 'f'), m(HealthLabel.WAITING_DEVICE, "waitingForDevice"), m(HealthLabel.OLDEST_WAIT, "oldestDeviceWaitAgeSeconds", 's'), m(HealthLabel.UNCERTAIN, "uncertainAttempts"))),
            inference?.obj("triage").panel(HealthArea.CLOUD_TRIAGE, jobs, status = false),
            inference?.obj("chat").panel(HealthArea.CLOUD_CHAT, jobs, status = false),
            inference?.obj("allowance").panel(HealthArea.ALLOWANCE, listOf(m(HealthLabel.OBSERVED_AT, "observedAt", 't'))),
            data.obj("sync").panel(HealthArea.SYNC, listOf(m(HealthLabel.LATEST_CHANGE, "latestChangeAt", 't'), m(HealthLabel.LAST_DEVICE_SYNC, "lastDeviceSyncAt", 't'))),
            data.obj("notifications").panel(HealthArea.NOTIFICATIONS, listOf(m(HealthLabel.PUSH_CONFIGURED, "adapterConfigured", 'f'), m(HealthLabel.DELIVERABLE_DEVICES, "deliverableDevices"), m(HealthLabel.LAST_SUBMITTED, "lastSubmittedAt", 't'))),
            data.obj("outbox").panel(HealthArea.OUTBOX, listOf(m(HealthLabel.PENDING, "pending"), m(HealthLabel.OLDEST, "oldestAgeSeconds", 's'))),
            data.obj("backlog").panel(HealthArea.BACKLOG, listOf(m(HealthLabel.OLDEST, "oldestAgeSeconds", 's'), m(HealthLabel.DEAD, "deadJobs")), status = false),
        )
        val partitions = data.obj("storage")?.array("partitions")
        if (partitions.isNullOrEmpty()) panels += empty.panel(HealthArea.STORAGE, listOf(m(HealthLabel.SIZE, "sizeBytes", 'b'), m(HealthLabel.HEADROOM, "headroomBytes", 'b')))
        else partitions.forEachIndexed { index, element -> panels += (element as? JsonObject).panel(HealthArea.STORAGE, listOf(m(HealthLabel.SIZE, "sizeBytes", 'b'), m(HealthLabel.HEADROOM, "headroomBytes", 'b')), status = false, ordinal = index + 1) }
        val devices = data.array("devices")
        if (devices.isNullOrEmpty()) panels += empty.panel(HealthArea.DEVICE, listOf(m(HealthLabel.OBSERVED_AT, "readinessReportedAt", 't'), m(HealthLabel.MODEL_LOADED, "modelLoaded", 'f')))
        else devices.forEachIndexed { index, element ->
            val device = element as? JsonObject
            panels += device.panel(HealthArea.DEVICE, listOf(m(HealthLabel.OBSERVED_AT, "readinessReportedAt", 't'), m(HealthLabel.MODEL_LOADED, "modelLoaded", 'f'), m(HealthLabel.LAST_SYNC, "lastSyncAt", 't'), m(HealthLabel.PUSH_PERMISSION, "push", 'c')), ordinal = index + 1).copy(status = device?.status("readiness") ?: HealthStatus.UNOBSERVED)
        }
        panels += data.panel(HealthArea.DIAGNOSTICS, listOf(m(HealthLabel.WARNING_COUNT, "warnings", 'a')), status = false)
        val warnings = data.array("warnings").orEmpty().mapNotNull { element ->
            val code = (element as? JsonObject)?.string("code")
            HealthWarning.entries.firstOrNull { it.name.lowercase(java.util.Locale.ROOT) == code }
        }.distinct()
        return HealthReport(generated, panels, warnings)
    }

    fun diagnostics(text: String): DiagnosticReport {
        val data = data(text)
        val generated = requireNotNull(data.time("generatedAt"))
        val release = data.obj("release").panel(HealthArea.RELEASE, listOf(m(HealthLabel.HTTP_SCHEMA, "httpSchemaVersion"), m(HealthLabel.EVENT_SCHEMA, "eventSchemaVersion"), m(HealthLabel.CONTROL_MIGRATION, "controlMigration"), m(HealthLabel.ARCHIVE_MIGRATION, "archiveMigration")), status = false)
        val settings = data.obj("settings").panel(HealthArea.POLICY, listOf(m(HealthLabel.SETTINGS_REVISION, "revision"), m(HealthLabel.POLICY_REVISION, "policyRevision"), m(HealthLabel.MODE, "inferenceMode", 'c')), status = false)
        val summary = HealthPanel(HealthArea.DIAGNOSTICS, null, listOf(
            (data.obj("migrations") ?: empty).metric(HealthLabel.APPLIED_MIGRATIONS, "control", 'a'),
            (data.obj("models") ?: empty).metric(HealthLabel.MODEL_REPORTS, "devices", 'a'),
            data.metric(HealthLabel.APP_REPORTS, "apps", 'a'),
            data.metric(HealthLabel.RECENT_FAILURES, "recentFailures", 'a'),
            (data.obj("uncertain") ?: empty).metric(HealthLabel.UNCERTAIN_INFERENCE, "inferenceAttempts", 'a'),
            (data.obj("uncertain") ?: empty).metric(HealthLabel.UNCERTAIN_OPERATIONS, "operations", 'a'),
            (data.obj("uncertain") ?: empty).metric(HealthLabel.UNCERTAIN_NOTIFICATIONS, "notifications", 'a'),
        ))
        val timing = data.obj("timings")?.obj("lastDay")
        val durations = listOf(m(HealthLabel.COUNT, "count"), m(HealthLabel.MEDIAN, "p50Ms", 'm'), m(HealthLabel.MAXIMUM, "maxMs", 'm'))
        return DiagnosticReport(generated, listOf(release, settings, summary,
            timing?.obj("cloudInference").panel(HealthArea.CLOUD_TIMING, durations, status = false),
            timing?.obj("localInference").panel(HealthArea.LOCAL_TIMING, durations, status = false),
            timing?.obj("chatTurns").panel(HealthArea.CHAT_TIMING, durations, status = false)))
    }
}
