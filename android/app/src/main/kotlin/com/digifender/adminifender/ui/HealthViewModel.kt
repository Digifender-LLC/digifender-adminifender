package com.digifender.adminifender.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digifender.adminifender.core.data.health.DiagnosticReport
import com.digifender.adminifender.core.data.health.HealthReport
import com.digifender.adminifender.core.data.health.HealthRepository
import com.digifender.adminifender.core.data.network.ReadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HealthUiState(
    val now: Long,
    val report: HealthReport? = null,
    val diagnostics: DiagnosticReport? = null,
    val local: HealthDeviceSnapshot = HealthDeviceSnapshot(),
    val loading: Boolean = false,
    val diagnosticsLoading: Boolean = false,
    val unavailable: Boolean = false,
    val diagnosticsUnavailable: Boolean = false,
    val signInRequired: Boolean = false,
) {
    val oldObservation: Boolean get() = report?.let { unavailable || loading || it.staleAt(now) } ?: false
}

/** No background requests, automatic retry or saved operational data. Session failure clears both read surfaces. */
class HealthViewModel(
    private val health: HealthRepository,
    private val probe: HealthDeviceProbe,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HealthUiState(clock()))
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var visible = false
    private var read: Job? = null
    private var diagnosticRead: Job? = null
    private var ticker: Job? = null

    fun onForeground() {
        if (visible) return
        visible = true
        refresh()
        ticker = viewModelScope.launch { while (true) { delay(15_000); observeLocal() } }
    }

    fun onBackground() {
        visible = false
        generation++
        read?.cancel(); diagnosticRead?.cancel(); ticker?.cancel()
        mutableState.value = HealthUiState(clock())
    }

    private fun observeLocal() {
        val local = runCatching { probe.read() }.getOrDefault(HealthDeviceSnapshot())
        mutableState.update { it.copy(now = clock(), local = local) }
    }

    fun refresh() {
        if (!visible || read?.isActive == true) return
        observeLocal()
        val started = generation
        mutableState.update { it.copy(loading = true, signInRequired = false) }
        read = viewModelScope.launch {
            val result = safe { health.refresh() }
            if (!visible || generation != started) return@launch
            when (result) {
                is ReadResult.Ok -> mutableState.update { it.copy(report = result.value, loading = false, unavailable = false, now = clock()) }
                ReadResult.SessionExpired -> sessionExpired()
                else -> mutableState.update { it.copy(loading = false, unavailable = true, now = clock()) }
            }
        }
    }

    fun loadDiagnostics() {
        if (!visible || state.value.signInRequired || diagnosticRead?.isActive == true) return
        val started = generation
        mutableState.update { it.copy(diagnosticsLoading = true, diagnosticsUnavailable = false, diagnostics = null) }
        diagnosticRead = viewModelScope.launch {
            val result = safe { health.diagnostics() }
            if (!visible || generation != started) return@launch
            when (result) {
                is ReadResult.Ok -> mutableState.update { it.copy(diagnostics = result.value, diagnosticsLoading = false, now = clock()) }
                ReadResult.SessionExpired -> sessionExpired()
                else -> mutableState.update { it.copy(diagnosticsLoading = false, diagnosticsUnavailable = true, now = clock()) }
            }
        }
    }

    private fun sessionExpired() {
        generation++
        mutableState.value = HealthUiState(clock(), local = state.value.local, signInRequired = true)
        read?.cancel(); diagnosticRead?.cancel()
    }

    private suspend fun <T> safe(block: suspend () -> ReadResult<T>): ReadResult<T> = try {
        block()
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        ReadResult.Unavailable("health_unavailable")
    }
}
