package com.digifender.adminifender.inference

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import com.digifender.adminifender.core.data.device.LocalModel
import com.digifender.adminifender.core.model.Readiness
import com.digifender.adminifender.core.model.Thermal
import java.util.concurrent.atomic.AtomicInteger

/** OS-reported readiness: battery, charging, thermal status and whether an activity is in the foreground. */
class DeviceProbe(private val context: Context) : Application.ActivityLifecycleCallbacks {
    private val started = AtomicInteger(0)

    fun readiness(model: LocalModel?): Readiness {
        val battery = context.getSystemService(BatteryManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        return Readiness(
            foreground = started.get() > 0,
            charging = battery.isCharging,
            batteryPercent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100),
            thermal = Thermal.fromStatus(power.currentThermalStatus),
            modelId = model?.manifest?.modelId,
            modelManifestHash = model?.manifestHash,
        )
    }

    override fun onActivityStarted(activity: Activity) {
        started.incrementAndGet()
    }

    override fun onActivityStopped(activity: Activity) {
        started.decrementAndGet()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
