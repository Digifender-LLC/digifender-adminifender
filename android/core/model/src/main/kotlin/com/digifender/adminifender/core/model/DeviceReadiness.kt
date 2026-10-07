package com.digifender.adminifender.core.model

/** OS observations only. A missing observation never proves readiness (FR-023). */
enum class DeviceThermal(val wire: String) {
    NONE("none"), LIGHT("light"), MODERATE("moderate"), SEVERE("severe"),
    CRITICAL("critical"), EMERGENCY("emergency"), SHUTDOWN("shutdown"),
}

data class DeviceConditions(
    val foreground: Boolean,
    val charging: Boolean?,
    val batteryPercent: Int?,
    val thermal: DeviceThermal?,
) {
    fun deferral(interactive: Boolean, modelReady: Boolean): String? = when {
        !modelReady -> "model_unavailable"
        charging == null || batteryPercent == null || thermal == null || batteryPercent !in 0..100 -> "telemetry_unavailable"
        batteryPercent < 30 -> "battery_low"
        thermal.ordinal >= DeviceThermal.SEVERE.ordinal -> "thermal"
        interactive && !foreground -> "foreground_required"
        !interactive && !charging -> "charging_required"
        else -> null
    }
}
