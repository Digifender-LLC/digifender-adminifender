package com.digifender.adminifender.core.data.health

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthWireTest {
    private val now = Instant.parse("2026-10-07T10:00:00Z").toEpochMilli()
    private fun health(fields: String = "") = HealthWire.health("""{"data":{"generatedAt":"2026-10-07T10:00:00Z"$fields}}""")

    @Test
    fun partialReportPreservesUnknownFieldsSeparatelyFromObservedZero() {
        val report = health(""", "capture":{"status":"warning","waiting":0,"oldestWaitingAgeSeconds":null},"scheduler":{"status":"unobserved","lastPassAt":null}""")
        val capture = report.panels.first { it.area == HealthArea.CAPTURE }
        assertEquals(HealthStatus.WARNING, capture.status)
        assertEquals(HealthValue.Number(0), capture.metrics.first { it.label == HealthLabel.WAITING }.value)
        assertEquals(HealthValue.Unknown, capture.metrics.first { it.label == HealthLabel.OLDEST_WAIT }.value)
        assertEquals(HealthValue.Unknown, capture.metrics.first { it.label == HealthLabel.QUARANTINED }.value)
        val extraction = report.panels.first { it.area == HealthArea.EXTRACTION }
        assertEquals(HealthStatus.UNOBSERVED, extraction.status)
        assertTrue(extraction.metrics.all { it.value == HealthValue.Unknown })
    }

    @Test
    fun unknownStatusAndIllTypedValuesNeverBecomeSuccessOrZero() {
        val report = health(""", "inference":{"status":"secret-synthetic-marker","cloudBound":"true","waitingForDevice":-1,"uncertainAttempts":"0"}""")
        val inference = report.panels.first { it.area == HealthArea.INFERENCE }
        assertEquals(HealthStatus.UNAVAILABLE, inference.status)
        assertTrue(inference.metrics.all { it.value == HealthValue.Unknown })
        assertFalse(report.toString().contains("secret-synthetic-marker"))
    }

    @Test
    fun deviceAndBudgetObservationsExpireEvenWhileServerSnapshotRemainsFresh() {
        val report = health(""", "devices":[{"readiness":"ok","readinessReportedAt":"2026-10-07T09:59:00Z","modelLoaded":true}],"inference":{"allowance":{"status":"unobserved","observedAt":null}}""")
        val device = report.panels.first { it.area == HealthArea.DEVICE }
        assertEquals(HealthStatus.OK, device.statusAt(now))
        assertEquals(HealthStatus.STALE, device.statusAt(now + 61_000))
        assertFalse(report.staleAt(now + 61_000))
        assertEquals(HealthStatus.UNOBSERVED, report.panels.first { it.area == HealthArea.ALLOWANCE }.statusAt(now))
        val budget = HealthPanel(HealthArea.ALLOWANCE, HealthStatus.OK, listOf(HealthMetric(HealthLabel.OBSERVED_AT, HealthValue.Time(now))))
        assertEquals(HealthStatus.STALE, budget.statusAt(now + 300_001))
        assertEquals(HealthStatus.UNOBSERVED, budget.copy(metrics = emptyList()).statusAt(now))
    }

    @Test
    fun staleOrImplausiblyFutureSnapshotIsNotCurrent() {
        val report = health()
        assertTrue(report.staleAt(now + 300_001))
        assertTrue(report.staleAt(now - 60_001))
        assertFalse(report.staleAt(now))
    }

    @Test
    fun warningAndDiagnosticsProjectionDropsContentIdentifiersAndRawFailures() {
        val report = health(""", "warnings":[{"code":"push_errors","component":"secret-synthetic-marker"},{"code":"secret-synthetic-marker"}],"content":"secret-synthetic-marker"""")
        assertEquals(listOf(HealthWarning.PUSH_ERRORS), report.warnings)
        assertFalse(report.toString().contains("secret-synthetic-marker"))
        val diagnostic = HealthWire.diagnostics("""{"data":{"generatedAt":"2026-10-07T10:00:00Z","release":{"httpSchemaVersion":1,"eventSchemaVersion":1,"controlMigration":10,"archiveMigration":1},"settings":{"revision":3,"policyRevision":2,"inferenceMode":"no_ai"},"models":{"cloud":"secret-synthetic-marker","devices":[{"modelId":"secret-synthetic-marker"}]},"apps":[{"appVersion":"secret-synthetic-marker"}],"recentFailures":[{"jobId":"secret-synthetic-marker","errorCode":"secret-synthetic-marker"}],"uncertain":{"inferenceAttempts":["secret-synthetic-marker"],"operations":[],"notifications":[]},"sourceText":"secret-synthetic-marker"}}""")
        assertFalse(diagnostic.toString().contains("secret-synthetic-marker"))
        assertEquals(HealthValue.Choice(HealthChoice.NO_AI), diagnostic.panels.first { it.area == HealthArea.POLICY }.metrics.last().value)
        assertEquals(HealthValue.Number(1), diagnostic.panels.first { it.area == HealthArea.DIAGNOSTICS }.metrics.first { it.label == HealthLabel.RECENT_FAILURES }.value)
    }

    @Test
    fun absentMeasurementsAreNotZeroDuration() {
        val diagnostic = HealthWire.diagnostics("""{"data":{"generatedAt":"2026-10-07T10:00:00Z","timings":{"lastDay":{"cloudInference":{"count":0,"p50Ms":null,"maxMs":null}}}}}""")
        val timing = diagnostic.panels.first { it.area == HealthArea.CLOUD_TIMING }
        assertEquals(listOf(HealthValue.Number(0), HealthValue.Unknown, HealthValue.Unknown), timing.metrics.map { it.value })
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingGenerationTimeCannotEstablishObservation() { HealthWire.health("""{"data":{"scheduler":{"status":"ok"}}}""") }

    @Test(expected = IllegalArgumentException::class)
    fun excessiveMetadataArrayIsRefused() { health(",\"devices\":[" + List(201) { "{}" }.joinToString() + "]") }
}
