package com.digifender.adminifender.core.data.platform

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.digifender.adminifender.core.data.devicework.DeviceConditionsSource
import com.digifender.adminifender.core.model.DeviceConditions
import com.digifender.adminifender.core.model.DeviceThermal

/** Foreground is updated by the activity lifecycle; WorkManager never asserts foreground on its own. */
class AndroidDeviceConditions(context: Context) : DeviceConditionsSource {
    private val app = context.applicationContext
    @Volatile private var foreground = false

    fun setForeground(value: Boolean) { foreground = value }

    override fun current(): DeviceConditions {
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0 && level <= scale) (level.toLong() * 100 / scale).toInt() else null
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> null
        }
        val thermal = when (app.getSystemService(PowerManager::class.java)?.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> DeviceThermal.NONE
            PowerManager.THERMAL_STATUS_LIGHT -> DeviceThermal.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> DeviceThermal.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> DeviceThermal.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL -> DeviceThermal.CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> DeviceThermal.EMERGENCY
            PowerManager.THERMAL_STATUS_SHUTDOWN -> DeviceThermal.SHUTDOWN
            else -> null
        }
        return DeviceConditions(foreground, charging, percent, thermal)
    }
}
