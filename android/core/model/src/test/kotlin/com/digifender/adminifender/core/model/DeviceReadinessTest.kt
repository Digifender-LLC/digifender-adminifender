package com.digifender.adminifender.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceReadinessTest {
    private val charging = DeviceConditions(false, true, 70, DeviceThermal.NONE)

    @Test
    fun requiresAnActualRuntimeAndCurrentOsReadiness() {
        assertEquals("model_unavailable", charging.deferral(false, false))
        assertNull(charging.deferral(false, true))
        assertEquals("battery_low", charging.copy(batteryPercent = 29).deferral(false, true))
        assertEquals("thermal", charging.copy(thermal = DeviceThermal.SEVERE).deferral(false, true))
        assertEquals("telemetry_unavailable", charging.copy(thermal = null).deferral(false, true))
        assertEquals("telemetry_unavailable", charging.copy(batteryPercent = null).deferral(false, true))
        assertEquals("charging_required", charging.copy(charging = false).deferral(false, true))
    }

    @Test
    fun foregroundAllowsInteractiveWorkOnly() {
        val foreground = charging.copy(foreground = true, charging = false)
        assertNull(foreground.deferral(true, true))
        assertEquals("charging_required", foreground.deferral(false, true))
        assertEquals("foreground_required", charging.deferral(true, true))
    }
}
