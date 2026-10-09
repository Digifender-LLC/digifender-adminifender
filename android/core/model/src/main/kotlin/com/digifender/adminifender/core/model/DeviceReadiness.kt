package com.digifender.adminifender.core.model

/** Android thermal status names, in the server's order (`THERMAL_STATES` in backend routing). */
enum class Thermal(val wire: String) {
    NONE("none"),
    LIGHT("light"),
    MODERATE("moderate"),
    SEVERE("severe"),
    CRITICAL("critical"),
    EMERGENCY("emergency"),
    SHUTDOWN("shutdown"),
    ;

    companion object {
        /** Maps `PowerManager.THERMAL_STATUS_*` (0 to 6); unknown values count as the most severe. */
        fun fromStatus(status: Int): Thermal = entries.getOrNull(status) ?: SHUTDOWN
    }
}

/** OS-reported device state; a model's own claims never count (lld.md#triage). */
data class Readiness(
    val foreground: Boolean,
    val charging: Boolean,
    val batteryPercent: Int,
    val thermal: Thermal,
    val modelId: String?,
    val modelManifestHash: String?,
)

enum class WorkKind { INTERACTIVE, BACKGROUND }

const val MIN_BATTERY_PERCENT = 30

/**
 * The same gate the server applies before handing out work: a loaded model, battery at least 30 %, below severe
 * thermal state, and a foreground session for interactive work or charging for background work. The phone checks it
 * before claiming so it does not take a lease it would then hold idle.
 */
fun Readiness.admits(kind: WorkKind): Boolean {
    if (modelId == null || modelManifestHash == null) return false
    if (batteryPercent < MIN_BATTERY_PERCENT) return false
    if (thermal >= Thermal.SEVERE) return false
    return when (kind) {
        WorkKind.INTERACTIVE -> foreground
        WorkKind.BACKGROUND -> charging
    }
}

/** A running local task stops when the phone heats up to severe or drops below the battery floor. */
fun Readiness.mustStop(): Boolean = thermal >= Thermal.SEVERE || batteryPercent < MIN_BATTERY_PERCENT
