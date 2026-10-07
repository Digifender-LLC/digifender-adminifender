package com.digifender.adminifender.ui

import com.digifender.adminifender.core.data.health.DiagnosticReport
import com.digifender.adminifender.core.data.health.HealthApi
import com.digifender.adminifender.core.data.health.HealthArea
import com.digifender.adminifender.core.data.health.HealthPanel
import com.digifender.adminifender.core.data.health.HealthReport
import com.digifender.adminifender.core.data.health.HealthRepository
import com.digifender.adminifender.core.data.health.HealthStatus
import com.digifender.adminifender.core.data.network.ReadResult
import com.digifender.adminifender.core.model.NotificationChannelKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class UiHealthApi : HealthApi {
    var result: ReadResult<HealthReport> = ReadResult.Ok(HealthReport(1_000_000, listOf(HealthPanel(HealthArea.CAPTURE, HealthStatus.OK, emptyList()))))
    var diagnosticResult: ReadResult<DiagnosticReport> = ReadResult.Ok(DiagnosticReport(1_000_000, emptyList()))
    var healthGate: CompletableDeferred<Unit>? = null
    var diagnosticGate: CompletableDeferred<Unit>? = null
    var healthReads = 0
    var diagnosticReads = 0
    override suspend fun health(): ReadResult<HealthReport> {
        healthReads++
        val captured = result
        withContext(NonCancellable) { healthGate?.await() }
        return captured
    }
    override suspend fun diagnostics(): ReadResult<DiagnosticReport> {
        diagnosticReads++
        val captured = diagnosticResult
        withContext(NonCancellable) { diagnosticGate?.await() }
        return captured
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HealthViewModelTest {
    private val api = UiHealthApi()
    private var now = 1_000_000L
    private var local = HealthDeviceSnapshot(notificationsEnabled = false, channels = mapOf(NotificationChannelKind.CRITICAL to false))
    private fun model(probe: HealthDeviceProbe = HealthDeviceProbe { local }) = HealthViewModel(HealthRepository(api), probe) { now }
    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    @Test fun localPermissionDoesNotFabricateServerOrModelHealthWhileOffline() = runTest {
        api.result = ReadResult.Unavailable("network")
        local = local.copy(notificationsEnabled = true, worker = HealthWorkerStatus.IDLE)
        val model = model()
        model.onForeground()
        assertTrue(model.state.value.unavailable)
        assertNull(model.state.value.report)
        assertEquals(true, model.state.value.local.notificationsEnabled)
        assertEquals(HealthStatus.UNOBSERVED, model.state.value.local.model)
        assertEquals(0, api.diagnosticReads)
        model.onBackground()
    }

    @Test fun failedRefreshKeepsPreviousObservationExplicitlyOldAndRetryCanRecover() = runTest {
        val model = model()
        model.onForeground()
        val previous = model.state.value.report
        assertFalse(model.state.value.oldObservation)
        api.result = ReadResult.Unavailable("secret-synthetic-marker")
        model.refresh()
        assertEquals(previous, model.state.value.report)
        assertTrue(model.state.value.unavailable)
        assertTrue(model.state.value.oldObservation)
        assertFalse(model.state.value.toString().contains("secret-synthetic-marker"))
        api.result = ReadResult.Ok(requireNotNull(previous))
        model.refresh()
        assertFalse(model.state.value.unavailable)
        assertFalse(model.state.value.oldObservation)
        model.onBackground()
    }

    @Test fun explicitDiagnosticFailureDoesNotEraseIndependentHealthObservation() = runTest {
        val model = model()
        model.onForeground()
        assertEquals(0, api.diagnosticReads)
        api.diagnosticResult = ReadResult.Unavailable("secret-synthetic-marker")
        model.loadDiagnostics()
        assertTrue(model.state.value.diagnosticsUnavailable)
        assertFalse(model.state.value.unavailable)
        assertTrue(model.state.value.report != null)
        assertNull(model.state.value.diagnostics)
        model.onBackground()
    }

    @Test fun clockTickAgesServerObservationWithoutAutomaticNetworkRequests() = runTest {
        val model = model()
        model.onForeground()
        now += 300_001
        local = local.copy(notificationsEnabled = true)
        advanceTimeBy(15_000); runCurrent()
        assertTrue(model.state.value.oldObservation)
        assertEquals(true, model.state.value.local.notificationsEnabled)
        assertEquals(1, api.healthReads)
        model.onBackground()
    }

    @Test fun sessionExpiryClearsBothSurfacesAndFencesDelayedOtherRead() = runTest {
        val model = model()
        model.onForeground()
        api.diagnosticGate = CompletableDeferred()
        model.loadDiagnostics()
        api.result = ReadResult.SessionExpired
        model.refresh()
        assertTrue(model.state.value.signInRequired)
        assertNull(model.state.value.report)
        assertNull(model.state.value.diagnostics)
        api.diagnosticGate!!.complete(Unit); runCurrent()
        assertNull(model.state.value.diagnostics)
        assertFalse(model.state.value.loading)
        assertFalse(model.state.value.diagnosticsLoading)
        model.onBackground()
    }

    @Test fun leavingScreenClearsObservationsAndLateResultCannotRestoreThem() = runTest {
        val model = model()
        api.healthGate = CompletableDeferred()
        model.onForeground()
        assertTrue(model.state.value.loading)
        model.onBackground()
        api.healthGate!!.complete(Unit); runCurrent()
        assertNull(model.state.value.report)
        assertEquals(HealthDeviceSnapshot(), model.state.value.local)
        assertFalse(model.state.value.loading)
        assertFalse(model.state.value.unavailable)
        model.refresh(); model.loadDiagnostics()
        assertEquals(1, api.healthReads)
        assertEquals(0, api.diagnosticReads)
    }

    @Test fun unavailableLocalProbeDoesNotReplaceAuthenticatedServerObservation() = runTest {
        val model = model(HealthDeviceProbe { error("secret-synthetic-marker") })
        model.onForeground()
        assertTrue(model.state.value.report != null)
        assertEquals(HealthDeviceSnapshot(), model.state.value.local)
        assertFalse(model.state.value.toString().contains("secret-synthetic-marker"))
        model.onBackground()
    }

    @Test fun refreshingKeepsOldReportLabeledAsPreviousUntilReadCompletes() = runTest {
        val model = model()
        model.onForeground()
        api.healthGate = CompletableDeferred()
        model.refresh()
        assertTrue(model.state.value.loading)
        assertTrue(model.state.value.oldObservation)
        model.refresh()
        assertEquals(2, api.healthReads)
        api.healthGate!!.complete(Unit); runCurrent()
        assertFalse(model.state.value.oldObservation)
        model.onBackground()
    }
}
