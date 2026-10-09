package com.digifender.adminifender.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceReadinessTest {
    private val ready = Readiness(foreground = true, charging = true, batteryPercent = 80, thermal = Thermal.MODERATE, modelId = "m", modelManifestHash = "a".repeat(64))

    @Test
    fun matchesTheServerGate() {
        assertTrue(ready.admits(WorkKind.INTERACTIVE))
        assertTrue(ready.admits(WorkKind.BACKGROUND))
        assertFalse(ready.copy(charging = false).admits(WorkKind.BACKGROUND))
        assertTrue(ready.copy(charging = false).admits(WorkKind.INTERACTIVE))
        assertFalse(ready.copy(foreground = false).admits(WorkKind.INTERACTIVE))
        assertFalse(ready.copy(batteryPercent = 29).admits(WorkKind.BACKGROUND))
        assertTrue(ready.copy(batteryPercent = 30).admits(WorkKind.BACKGROUND))
        assertFalse(ready.copy(thermal = Thermal.SEVERE).admits(WorkKind.INTERACTIVE))
        assertFalse(ready.copy(modelId = null).admits(WorkKind.INTERACTIVE))
    }

    @Test
    fun unknownThermalStatusIsTreatedAsTheWorst() {
        assertEquals(Thermal.NONE, Thermal.fromStatus(0))
        assertEquals(Thermal.SEVERE, Thermal.fromStatus(3))
        assertEquals(Thermal.SHUTDOWN, Thermal.fromStatus(42))
        assertTrue(ready.copy(thermal = Thermal.fromStatus(-1)).mustStop())
    }
}
