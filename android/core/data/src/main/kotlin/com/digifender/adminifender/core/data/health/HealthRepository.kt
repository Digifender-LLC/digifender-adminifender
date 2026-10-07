package com.digifender.adminifender.core.data.health

import com.digifender.adminifender.core.data.network.ReadResult

enum class HealthStatus { OK, WARNING, STALE, UNAVAILABLE, UNOBSERVED }
enum class HealthArea { SCHEDULER, CAPTURE, PARSING, TRIAGE, EXTRACTION, INFERENCE, CLOUD_TRIAGE, CLOUD_CHAT, ALLOWANCE, SYNC, NOTIFICATIONS, OUTBOX, STORAGE, BACKLOG, DEVICE, RELEASE, POLICY, DIAGNOSTICS, CLOUD_TIMING, LOCAL_TIMING, CHAT_TIMING }
enum class HealthLabel {
    LAST_PASS, LAST_RECEIPT, WAITING, OLDEST_WAIT, QUARANTINED, OPEN, OLDEST, DEAD, FAILED_DAY, PARSE_FAILED,
    DEFERRED_DEVICE, FAILED, UNSUPPORTED, CLOUD_BOUND, WAITING_DEVICE, UNCERTAIN, OBSERVED_AT, LATEST_CHANGE,
    LAST_DEVICE_SYNC, PUSH_CONFIGURED, DELIVERABLE_DEVICES, LAST_SUBMITTED, PENDING, SIZE, HEADROOM,
    MODEL_LOADED, PUSH_PERMISSION, LAST_SYNC, HTTP_SCHEMA, EVENT_SCHEMA, CONTROL_MIGRATION, ARCHIVE_MIGRATION,
    APPLIED_MIGRATIONS, SETTINGS_REVISION, POLICY_REVISION, MODE, MODEL_REPORTS, APP_REPORTS, RECENT_FAILURES,
    UNCERTAIN_INFERENCE, UNCERTAIN_OPERATIONS, UNCERTAIN_NOTIFICATIONS, COUNT, MEDIAN, MAXIMUM, WARNING_COUNT,
}
enum class HealthChoice { AUTO, LOCAL_ONLY, NO_AI, GRANTED, DENIED, UNREGISTERED, INVALID }
enum class HealthWarning {
    SCHEDULER_STALE, SCHEDULER_UNOBSERVED, CAPTURE_BACKLOG, PARSE_FAILED, PARSE_BACKLOG, TRIAGE_BACKLOG,
    EXTRACTION_BACKLOG, INFERENCE_BACKLOG, DEVICE_WORK_WAITING, INFERENCE_UNCERTAIN, PUSH_UNAVAILABLE,
    NO_PUSH_DEVICE, PUSH_ERRORS, OUTBOX_BACKLOG, PARTITION_CAPACITY,
}
sealed interface HealthValue {
    data object Unknown : HealthValue
    data class Number(val value: Long) : HealthValue
    data class Seconds(val value: Long) : HealthValue
    data class Milliseconds(val value: Long) : HealthValue
    data class Bytes(val value: Long) : HealthValue
    data class Time(val value: Long) : HealthValue
    data class Flag(val value: Boolean) : HealthValue
    data class Choice(val value: HealthChoice) : HealthValue
}
data class HealthMetric(val label: HealthLabel, val value: HealthValue)
data class HealthPanel(val area: HealthArea, val status: HealthStatus?, val metrics: List<HealthMetric>, val ordinal: Int? = null) {
    /** A previously current device/budget observation expires while the screen stays open. */
    fun statusAt(now: Long): HealthStatus? {
        if (status != HealthStatus.OK || area !in setOf(HealthArea.DEVICE, HealthArea.ALLOWANCE)) return status
        val observed = (metrics.firstOrNull { it.label == HealthLabel.OBSERVED_AT }?.value as? HealthValue.Time)?.value ?: return HealthStatus.UNOBSERVED
        return if (expired(observed, now, if (area == HealthArea.DEVICE) 120_000 else HEALTH_MAX_AGE_MS)) HealthStatus.STALE else status
    }
}
data class HealthReport(val generatedAt: Long, val panels: List<HealthPanel>, val warnings: List<HealthWarning> = emptyList()) {
    fun staleAt(now: Long): Boolean = expired(generatedAt, now, HEALTH_MAX_AGE_MS)
}
data class DiagnosticReport(val generatedAt: Long, val panels: List<HealthPanel>) {
    fun staleAt(now: Long): Boolean = expired(generatedAt, now, HEALTH_MAX_AGE_MS)
}
const val HEALTH_MAX_AGE_MS = 300_000L
private fun expired(observed: Long, now: Long, maxAge: Long) = observed > now + 60_000 || now - observed > maxAge

interface HealthApi {
    suspend fun health(): ReadResult<HealthReport>
    suspend fun diagnostics(): ReadResult<DiagnosticReport>
}

/** These are authenticated observations, never persisted or inferred from connectivity. */
class HealthRepository(private val api: HealthApi) {
    suspend fun refresh(): ReadResult<HealthReport> = api.health()
    suspend fun diagnostics(): ReadResult<DiagnosticReport> = api.diagnostics()
}
