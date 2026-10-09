package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.CleanupState
import com.digifender.adminifender.core.data.network.ExportAdmission
import com.digifender.adminifender.core.data.network.ExportScope
import com.digifender.adminifender.core.data.network.ExportState
import com.digifender.adminifender.core.data.network.ExportStatus
import com.digifender.adminifender.core.data.network.InvalidatedReason
import com.digifender.adminifender.core.data.network.OperationReceipt
import com.digifender.adminifender.core.data.network.OperationState
import com.digifender.adminifender.core.data.network.PreparedReview
import com.digifender.adminifender.core.data.network.ReviewEffect
import com.digifender.adminifender.core.data.network.ReviewRequest
import com.digifender.adminifender.core.data.network.ReviewState
import com.digifender.adminifender.core.data.network.WireException
import com.digifender.adminifender.core.data.network.parseConfirmed
import com.digifender.adminifender.core.data.network.parseExportRequest
import com.digifender.adminifender.core.data.network.parseExportScope
import com.digifender.adminifender.core.data.network.parseExportList
import com.digifender.adminifender.core.data.network.parseExportStatus
import com.digifender.adminifender.core.data.network.parseOperation
import com.digifender.adminifender.core.data.network.parsePreparedReview
import com.digifender.adminifender.core.data.network.parseReviewReadback
import com.digifender.adminifender.core.data.network.parseReviewRequest
import com.digifender.adminifender.core.data.network.toJson
import com.digifender.adminifender.core.data.network.wireJson
import java.io.File
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun names(contract: String, kind: String): Set<String> =
    File(System.getProperty("contractsDir") ?: error("contractsDir"), "fixtures/v1/$contract/$kind").listFiles().orEmpty().map { it.name.removeSuffix(".json") }.toSet()

private fun fixture(contract: String, kind: String, name: String): JsonElement = wireJson.parseToJsonElement(contractFile("fixtures/v1/$contract/$kind/$name.json"))

/**
 * Every shared fixture of the reviewed-action and export contracts is accounted for: each valid one parses, each invalid
 * one is refused with a [WireException] (so the phone treats it as an answer it cannot trust), and adding a fixture
 * without saying what the phone does with it fails here.
 */
class ReviewExportWireFixturesTest {
    private fun <T> allValid(contract: String, expected: Set<String>, parse: (JsonElement) -> T): Map<String, T> {
        assertEquals("$contract valid fixtures", expected, names(contract, "valid"))
        return expected.associateWith { parse(fixture(contract, "valid", it)) }
    }

    private fun allInvalid(contract: String, expected: Set<String>, parse: (JsonElement) -> Any?) {
        assertEquals("$contract invalid fixtures", expected, names(contract, "invalid"))
        for (name in expected) {
            try {
                parse(fixture(contract, "invalid", name))
                fail("$contract/$name should be refused")
            } catch (_: WireException) {
                // refused, as it must be
            }
        }
    }

    @Test
    fun reviewRequests() {
        val valid = allValid("review-request", setOf("archive-delete", "device-revoke"), ::parseReviewRequest)
        assertEquals(2, (valid.getValue("archive-delete") as ReviewRequest.Deletion).sourceIds.size)
        assertTrue(valid.getValue("device-revoke") is ReviewRequest.Revocation)
        allInvalid("review-request", setOf("delete-duplicate", "delete-empty", "delete-too-many", "extra-field", "revoke-with-source-ids", "unknown-effect"), ::parseReviewRequest)
    }

    @Test
    fun preparedReviews() {
        val valid = allValid("review-prepared", setOf("archive-delete-no-subject", "archive-delete", "device-revoke"), ::parsePreparedReview)
        val deletion = (valid.getValue("archive-delete") as PreparedReview.Deletion).review
        assertEquals(1, deletion.preview.expectedCount)
        assertEquals("Invoice 4471", deletion.preview.sources.single().subject)
        assertEquals(1, deletion.preview.attachments)
        assertEquals(1, deletion.concernRevisions.size)
        // A subject the Worker could not read is null, never an invented title.
        assertNull((valid.getValue("archive-delete-no-subject") as PreparedReview.Deletion).review.preview.sources.single().subject)
        assertTrue(valid.getValue("device-revoke") is PreparedReview.Revocation)
        allInvalid(
            "review-prepared",
            setOf("delete-with-device-preconditions", "delete-without-expected-count", "extra-field", "recent-auth-false", "revoke-with-source-params", "short-digest", "unknown-effect", "zero-revision"),
            ::parsePreparedReview,
        )
    }

    @Test
    fun readbacksCarryNoContentAndOnlyConsumedHasAnOperation() {
        val valid = allValid("review-readback", setOf("consumed-revoke", "consumed", "expired", "open"), ::parseReviewReadback)
        assertEquals(ReviewState.CONSUMED, valid.getValue("consumed").state)
        assertEquals(OperationState.SUCCEEDED, valid.getValue("consumed").operationState)
        assertNotNull(valid.getValue("consumed").consumedOperationId)
        assertEquals(ReviewEffect.DEVICE_REVOKE, valid.getValue("consumed-revoke").effect)
        for (name in listOf("open", "expired")) {
            assertNull(valid.getValue(name).consumedOperationId)
            assertNull(valid.getValue(name).operationState)
        }
        allInvalid(
            "review-readback",
            setOf("consumed-without-operation", "expired-with-operation-state", "leaks-params", "leaks-preview", "open-with-operation", "recent-auth-false", "unknown-operation-state", "unknown-state"),
            ::parseReviewReadback,
        )
    }

    @Test
    fun confirmations() {
        val valid = allValid("review-confirmed", setOf("archive-delete", "device-revoke"), ::parseConfirmed)
        assertTrue(valid.getValue("archive-delete").receipt is OperationReceipt.Deletion)
        assertTrue(valid.getValue("device-revoke").receipt is OperationReceipt.Revocation)
        allInvalid("review-confirmed", setOf("bad-operation-id", "claims-backup-erased", "missing-receipt", "mixed-receipt", "unsettled-state"), ::parseConfirmed)
    }

    @Test
    fun operationsAndTheScopeOfTheirCleanup() {
        val valid = allValid(
            "operation",
            setOf("delete-cleanup-complete", "delete-cleanup-failed", "delete-cleanup-partial", "delete-cleanup-pending", "delete-cleanup-unknown", "device-revoke-no-cleanup", "uncertain-without-receipt"),
            ::parseOperation,
        )
        assertEquals(CleanupState.COMPLETE, valid.getValue("delete-cleanup-complete").cleanup?.state)
        assertEquals(CleanupState.PARTIAL, valid.getValue("delete-cleanup-partial").cleanup?.state)
        assertEquals(CleanupState.FAILED, valid.getValue("delete-cleanup-failed").cleanup?.state)
        assertTrue(valid.getValue("delete-cleanup-unknown").cleanup!!.unrecorded >= 1)
        assertNull(valid.getValue("device-revoke-no-cleanup").cleanup)
        assertEquals(OperationState.UNCERTAIN, valid.getValue("uncertain-without-receipt").state)
        assertNull(valid.getValue("uncertain-without-receipt").receipt)
        allInvalid(
            "operation",
            setOf("backup-scope", "complete-with-failed", "delete-without-cleanup", "failed-without-failure", "pending-with-completed", "revoke-with-cleanup", "unknown-cleanup-state", "unknown-state", "unknown-without-unrecorded"),
            ::parseOperation,
        )
    }

    @Test
    fun exportScopes() {
        val valid = allValid("export-scope", setOf("archive-all", "archive-normal", "filter-sender-range", "sources-quarantine"), ::parseExportScope)
        assertEquals(ExportScope.Archive(ExportAdmission.ALL), valid.getValue("archive-all"))
        val filtered = valid.getValue("filter-sender-range") as ExportScope.Filtered
        assertNotNull(filtered.filter.fromMs)
        assertNotNull(filtered.filter.sender)
        assertEquals(ExportAdmission.QUARANTINE, valid.getValue("sources-quarantine").admission)
        // The phone's own serialization is what the contract accepts, and what it parses back to the same scope.
        for ((name, scope) in valid) assertEquals(name, scope, parseExportScope(scope.toJson()))
        allInvalid(
            "export-scope",
            setOf("archive-with-filter", "archive-without-admission", "filter-empty", "filter-unknown-criterion", "filter-uppercase-sender", "sources-duplicate", "sources-empty", "sources-too-many", "sources-with-filter", "unknown-admission", "unknown-kind"),
            ::parseExportScope,
        )
    }

    @Test
    fun exportRequestsDefaultToTheOrdinaryArchiveWithoutQuarantine() {
        val valid = allValid("export-request", setOf("archive-default-admission", "archive-with-quarantine", "empty", "filter-range", "sources-quarantine-only"), ::parseExportRequest)
        assertEquals(ExportScope.Archive(ExportAdmission.NORMAL), valid.getValue("empty"))
        assertEquals(ExportScope.Archive(ExportAdmission.NORMAL), valid.getValue("archive-default-admission"))
        assertEquals(ExportAdmission.NORMAL, valid.getValue("filter-range").admission)
        assertEquals(ExportAdmission.QUARANTINE, valid.getValue("sources-quarantine-only").admission)
        allInvalid(
            "export-request",
            setOf("admission-boolean", "archive-with-source-ids", "filter-empty", "filter-without-criteria", "scope-without-kind", "sources-empty", "sources-malformed-id", "unknown-top-field"),
            ::parseExportRequest,
        )
    }

    @Test
    fun exportLists() {
        val valid = allValid("export-list", setOf("empty", "every-state", "ready-and-failed"), ::parseExportList)
        assertEquals(emptyList<ExportStatus>(), valid.getValue("empty"))
        assertEquals(
            listOf(ExportState.PENDING, ExportState.RUNNING, ExportState.READY, ExportState.EXPIRED, ExportState.INVALIDATED),
            valid.getValue("every-state").map { it.state },
        )
        assertEquals(listOf(ExportState.READY, ExportState.FAILED), valid.getValue("ready-and-failed").map { it.state })
        allInvalid("export-list", setOf("exports-not-array", "item-with-extra-field", "missing-exports", "ready-without-counts", "too-many", "unknown-field"), ::parseExportList)
    }

    @Test
    fun exportStatuses() {
        val valid = allValid(
            "export-status",
            setOf("expired", "failed-too-large", "invalidated-admission-changed", "invalidated-deleted", "invalidated-legacy-without-scope", "pending-archive", "ready-sources-scope", "ready-with-omissions", "running-quarantine-only"),
            ::parseExportStatus,
        )
        assertEquals(ExportState.READY, valid.getValue("ready-with-omissions").state)
        assertEquals(2, valid.getValue("ready-with-omissions").omissions?.size)
        assertEquals("export_too_large", valid.getValue("failed-too-large").failureCode)
        assertEquals(InvalidatedReason.ADMISSION_CHANGED, valid.getValue("invalidated-admission-changed").invalidatedReason)
        assertNull(valid.getValue("invalidated-legacy-without-scope").scope)
        assertEquals(InvalidatedReason.SCOPE_UNKNOWN, valid.getValue("invalidated-legacy-without-scope").invalidatedReason)
        assertEquals(ExportAdmission.QUARANTINE, valid.getValue("running-quarantine-only").scope?.admission)
        assertNull(valid.getValue("pending-archive").counts)
        // What the phone stores is read back by the same parser to the same status.
        for ((name, status) in valid) assertEquals(name, status, parseExportStatus(status.toJson()))
        allInvalid(
            "export-status",
            setOf(
                "code-without-failure", "failed-without-code", "invalidated-without-reason", "leaks-object-key", "null-scope-pending", "pending-with-counts", "ready-without-counts",
                "ready-without-scope", "reason-without-invalidation", "scope-without-admission", "unknown-omission-reason",
            ),
            ::parseExportStatus,
        )
    }
}
