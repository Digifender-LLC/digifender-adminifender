package com.digifender.adminifender.ui

import com.digifender.adminifender.R
import com.digifender.adminifender.core.data.health.HealthArea
import com.digifender.adminifender.core.data.health.HealthLabel
import com.digifender.adminifender.core.data.health.HealthStatus
import com.digifender.adminifender.core.data.health.HealthWarning
import com.digifender.adminifender.core.data.health.HealthChoice
import com.digifender.adminifender.core.model.DeviceThermal

internal fun healthAreaLabel(value: HealthArea): Int = when (value) {
    HealthArea.SCHEDULER -> R.string.health_area_scheduler
    HealthArea.CAPTURE -> R.string.health_area_capture
    HealthArea.PARSING -> R.string.health_area_parsing
    HealthArea.TRIAGE -> R.string.health_area_triage
    HealthArea.EXTRACTION -> R.string.health_area_extraction
    HealthArea.INFERENCE -> R.string.health_area_inference
    HealthArea.CLOUD_TRIAGE -> R.string.health_area_cloud_triage
    HealthArea.CLOUD_CHAT -> R.string.health_area_cloud_chat
    HealthArea.ALLOWANCE -> R.string.health_area_allowance
    HealthArea.SYNC -> R.string.health_area_sync
    HealthArea.NOTIFICATIONS -> R.string.health_area_notifications
    HealthArea.OUTBOX -> R.string.health_area_outbox
    HealthArea.STORAGE -> R.string.health_area_storage
    HealthArea.BACKLOG -> R.string.health_area_backlog
    HealthArea.DEVICE -> R.string.health_area_device
    HealthArea.RELEASE -> R.string.health_area_release
    HealthArea.POLICY -> R.string.health_area_policy
    HealthArea.DIAGNOSTICS -> R.string.health_area_diagnostics
    HealthArea.CLOUD_TIMING -> R.string.health_area_cloud_timing
    HealthArea.LOCAL_TIMING -> R.string.health_area_local_timing
    HealthArea.CHAT_TIMING -> R.string.health_area_chat_timing
}

internal fun healthMetricLabel(value: HealthLabel): Int = when (value) {
    HealthLabel.LAST_PASS -> R.string.health_metric_last_pass
    HealthLabel.LAST_RECEIPT -> R.string.health_metric_last_receipt
    HealthLabel.WAITING -> R.string.health_metric_waiting
    HealthLabel.OLDEST_WAIT -> R.string.health_metric_oldest_wait
    HealthLabel.QUARANTINED -> R.string.health_metric_quarantined
    HealthLabel.OPEN -> R.string.health_metric_open
    HealthLabel.OLDEST -> R.string.health_metric_oldest
    HealthLabel.DEAD -> R.string.health_metric_dead
    HealthLabel.FAILED_DAY -> R.string.health_metric_failed_day
    HealthLabel.PARSE_FAILED -> R.string.health_metric_parse_failed
    HealthLabel.DEFERRED_DEVICE -> R.string.health_metric_deferred_device
    HealthLabel.FAILED -> R.string.health_metric_failed
    HealthLabel.UNSUPPORTED -> R.string.health_metric_unsupported
    HealthLabel.CLOUD_BOUND -> R.string.health_metric_cloud_bound
    HealthLabel.WAITING_DEVICE -> R.string.health_metric_waiting_device
    HealthLabel.UNCERTAIN -> R.string.health_metric_uncertain
    HealthLabel.OBSERVED_AT -> R.string.health_metric_observed_at
    HealthLabel.LATEST_CHANGE -> R.string.health_metric_latest_change
    HealthLabel.LAST_DEVICE_SYNC -> R.string.health_metric_last_device_sync
    HealthLabel.PUSH_CONFIGURED -> R.string.health_metric_push_configured
    HealthLabel.DELIVERABLE_DEVICES -> R.string.health_metric_deliverable_devices
    HealthLabel.LAST_SUBMITTED -> R.string.health_metric_last_submitted
    HealthLabel.PENDING -> R.string.health_metric_pending
    HealthLabel.SIZE -> R.string.health_metric_size
    HealthLabel.HEADROOM -> R.string.health_metric_headroom
    HealthLabel.MODEL_LOADED -> R.string.health_metric_model_loaded
    HealthLabel.PUSH_PERMISSION -> R.string.health_metric_push_permission
    HealthLabel.LAST_SYNC -> R.string.health_metric_last_sync
    HealthLabel.HTTP_SCHEMA -> R.string.health_metric_http_schema
    HealthLabel.EVENT_SCHEMA -> R.string.health_metric_event_schema
    HealthLabel.CONTROL_MIGRATION -> R.string.health_metric_control_migration
    HealthLabel.ARCHIVE_MIGRATION -> R.string.health_metric_archive_migration
    HealthLabel.APPLIED_MIGRATIONS -> R.string.health_metric_applied_migrations
    HealthLabel.SETTINGS_REVISION -> R.string.health_metric_settings_revision
    HealthLabel.POLICY_REVISION -> R.string.health_metric_policy_revision
    HealthLabel.MODE -> R.string.health_metric_mode
    HealthLabel.MODEL_REPORTS -> R.string.health_metric_model_reports
    HealthLabel.APP_REPORTS -> R.string.health_metric_app_reports
    HealthLabel.RECENT_FAILURES -> R.string.health_metric_recent_failures
    HealthLabel.UNCERTAIN_INFERENCE -> R.string.health_metric_uncertain_inference
    HealthLabel.UNCERTAIN_OPERATIONS -> R.string.health_metric_uncertain_operations
    HealthLabel.UNCERTAIN_NOTIFICATIONS -> R.string.health_metric_uncertain_notifications
    HealthLabel.COUNT -> R.string.health_metric_count
    HealthLabel.MEDIAN -> R.string.health_metric_median
    HealthLabel.MAXIMUM -> R.string.health_metric_maximum
    HealthLabel.WARNING_COUNT -> R.string.health_metric_warning_count
}

internal fun healthStatusLabel(value: HealthStatus): Int = when (value) {
    HealthStatus.OK -> R.string.health_status_ok
    HealthStatus.WARNING -> R.string.health_status_warning
    HealthStatus.STALE -> R.string.health_status_stale
    HealthStatus.UNAVAILABLE -> R.string.health_status_unavailable
    HealthStatus.UNOBSERVED -> R.string.health_status_unobserved
}

internal fun healthChoiceLabel(value: HealthChoice): Int = when (value) {
    HealthChoice.AUTO -> R.string.health_choice_auto
    HealthChoice.LOCAL_ONLY -> R.string.health_choice_local_only
    HealthChoice.NO_AI -> R.string.health_choice_no_ai
    HealthChoice.GRANTED -> R.string.health_choice_granted
    HealthChoice.DENIED -> R.string.health_choice_denied
    HealthChoice.UNREGISTERED -> R.string.health_choice_unregistered
    HealthChoice.INVALID -> R.string.health_choice_invalid
}

internal fun healthWorkerLabel(value: HealthWorkerStatus): Int = when (value) {
    HealthWorkerStatus.IDLE -> R.string.health_worker_idle
    HealthWorkerStatus.DEFERRED -> R.string.health_worker_deferred
    HealthWorkerStatus.SIGN_IN_REQUIRED -> R.string.health_worker_sign_in_required
    HealthWorkerStatus.RUNNING -> R.string.health_worker_running
    HealthWorkerStatus.FINISHED -> R.string.health_worker_finished
    HealthWorkerStatus.UNCERTAIN -> R.string.health_worker_uncertain
    HealthWorkerStatus.UNAVAILABLE -> R.string.health_worker_unavailable
}

internal fun healthThermalLabel(value: DeviceThermal): Int = when (value) {
    DeviceThermal.NONE -> R.string.health_thermal_none
    DeviceThermal.LIGHT -> R.string.health_thermal_light
    DeviceThermal.MODERATE -> R.string.health_thermal_moderate
    DeviceThermal.SEVERE -> R.string.health_thermal_severe
    DeviceThermal.CRITICAL -> R.string.health_thermal_critical
    DeviceThermal.EMERGENCY -> R.string.health_thermal_emergency
    DeviceThermal.SHUTDOWN -> R.string.health_thermal_shutdown
}

internal fun healthWarningLabel(value: HealthWarning): Int = when (value) {
    HealthWarning.SCHEDULER_STALE -> R.string.health_warning_scheduler_stale
    HealthWarning.SCHEDULER_UNOBSERVED -> R.string.health_warning_scheduler_unobserved
    HealthWarning.CAPTURE_BACKLOG -> R.string.health_warning_capture_backlog
    HealthWarning.PARSE_FAILED -> R.string.health_warning_parse_failed
    HealthWarning.PARSE_BACKLOG -> R.string.health_warning_parse_backlog
    HealthWarning.TRIAGE_BACKLOG -> R.string.health_warning_triage_backlog
    HealthWarning.EXTRACTION_BACKLOG -> R.string.health_warning_extraction_backlog
    HealthWarning.INFERENCE_BACKLOG -> R.string.health_warning_inference_backlog
    HealthWarning.DEVICE_WORK_WAITING -> R.string.health_warning_device_work_waiting
    HealthWarning.INFERENCE_UNCERTAIN -> R.string.health_warning_inference_uncertain
    HealthWarning.PUSH_UNAVAILABLE -> R.string.health_warning_push_unavailable
    HealthWarning.NO_PUSH_DEVICE -> R.string.health_warning_no_push_device
    HealthWarning.PUSH_ERRORS -> R.string.health_warning_push_errors
    HealthWarning.OUTBOX_BACKLOG -> R.string.health_warning_outbox_backlog
    HealthWarning.PARTITION_CAPACITY -> R.string.health_warning_partition_capacity
}
