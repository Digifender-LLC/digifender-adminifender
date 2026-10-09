package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.BatchStatus
import com.digifender.adminifender.core.data.network.ClaimResponse
import com.digifender.adminifender.core.data.network.DeviceKind
import com.digifender.adminifender.core.data.network.ExtractionBatch
import com.digifender.adminifender.core.data.network.ExtractionOutcome
import com.digifender.adminifender.core.data.network.NoWorkReason
import com.digifender.adminifender.core.data.network.PageMethod
import com.digifender.adminifender.core.data.network.PageText
import com.digifender.adminifender.core.data.network.WireException
import com.digifender.adminifender.core.data.network.batchRequestBody
import com.digifender.adminifender.core.data.network.claimRequestBody
import com.digifender.adminifender.core.data.network.outcomeRequestBody
import com.digifender.adminifender.core.data.network.parseBatchRequest
import com.digifender.adminifender.core.data.network.parseBatchResult
import com.digifender.adminifender.core.data.network.parseClaim
import com.digifender.adminifender.core.data.network.parseClaimRequest
import com.digifender.adminifender.core.data.network.parseOutcomeRequest
import com.digifender.adminifender.core.data.network.wireJson
import java.io.File
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun fixtureNames(contract: String, kind: String): Set<String> =
    File(System.getProperty("contractsDir") ?: error("contractsDir"), "fixtures/v1/$contract/$kind").listFiles().orEmpty().map { it.name.removeSuffix(".json") }.toSet()

private fun extractionFixture(contract: String, kind: String, name: String): JsonElement = wireJson.parseToJsonElement(contractFile("fixtures/v1/$contract/$kind/$name.json"))

/**
 * Every shared fixture of the device extraction contracts is accounted for: each valid one parses, each invalid one is
 * refused with a [WireException], and a fixture added without saying what the phone does with it fails here.
 */
class ExtractionWireFixturesTest {
    private fun <T> allValid(contract: String, expected: Set<String>, parse: (JsonElement) -> T): Map<String, T> {
        assertEquals("$contract valid fixtures", expected, fixtureNames(contract, "valid"))
        return expected.associateWith { parse(extractionFixture(contract, "valid", it)) }
    }

    private fun allInvalid(contract: String, expected: Set<String>, parse: (JsonElement) -> Any?) {
        assertEquals("$contract invalid fixtures", expected, fixtureNames(contract, "invalid"))
        for (name in expected) {
            try {
                parse(extractionFixture(contract, "invalid", name))
                fail("$contract/$name should be refused")
            } catch (_: WireException) {
                // refused, as it must be
            }
        }
    }

    @Test
    fun claimRequests() {
        val valid = allValid("extraction-claim-request", setOf("both", "ocr-only", "neither"), ::parseClaimRequest)
        assertTrue(valid.getValue("both").pdfText && valid.getValue("both").ocr)
        assertFalse(valid.getValue("ocr-only").pdfText)
        allInvalid("extraction-claim-request", setOf("missing-ocr", "version-uppercase", "unknown-field", "flag-not-boolean"), ::parseClaimRequest)
        // What the phone sends is what the contract accepts.
        for (request in valid.values) assertEquals(request, parseClaimRequest(claimRequestBody(request)))
    }

    @Test
    fun claimAnswers() {
        val valid = allValid("extraction-claim", setOf("work-fresh", "work-resumed", "no-work", "busy"), ::parseClaim)
        val fresh = (valid.getValue("work-fresh") as ClaimResponse.Work).work
        assertEquals(DeviceKind.PDF, fresh.kind)
        assertEquals(0, fresh.nextBatchOrdinal)
        assertEquals(1, fresh.nextPage)
        assertTrue(fresh.ocrAllowed)
        val resumed = (valid.getValue("work-resumed") as ClaimResponse.Work).work
        assertEquals(DeviceKind.IMAGE, resumed.kind)
        assertFalse(resumed.ocrAllowed)
        assertEquals(2, resumed.batchHashes.size)
        assertEquals(21, resumed.nextPage)
        assertEquals(NoWorkReason.NO_WORK, (valid.getValue("no-work") as ClaimResponse.None).reason)
        assertEquals(NoWorkReason.BUSY, (valid.getValue("busy") as ClaimResponse.None).reason)
        allInvalid(
            "extraction-claim",
            setOf("null-work-without-reason", "work-with-reason", "unknown-reason", "kind-docx", "short-hash", "limit-changed", "missing-lease-token", "hash-list-not-hex"),
            ::parseClaim,
        )
    }

    @Test
    fun batchRequests() {
        val valid = allValid(
            "extraction-batch",
            setOf("first-batch", "ocr-with-confidence-and-parts", "blank-pages-only", "final-batch", "final-batch-first-and-only"),
            ::parseBatchRequest,
        )
        assertEquals(1, valid.getValue("first-batch").batch.pages.size)
        val parts = valid.getValue("ocr-with-confidence-and-parts").batch.pages
        assertEquals(listOf(0, 1), parts.map { it.part })
        assertEquals(0.82, parts.first().confidence!!, 0.0)
        assertNull(parts.last().confidence)
        assertTrue(valid.getValue("blank-pages-only").batch.pages.isEmpty())
        assertEquals(4, valid.getValue("blank-pages-only").batch.lastPage)
        val final = valid.getValue("final-batch").batch
        assertTrue(final.complete)
        assertEquals(40, final.totalPages)
        assertEquals("b".repeat(64), final.manifestHash)
        allInvalid(
            "extraction-batch",
            setOf(
                "complete-without-manifest", "manifest-on-open-batch", "total-pages-on-open-batch", "unknown-method", "empty-text", "page-zero", "page-beyond-limit",
                "confidence-above-one", "missing-last-page", "unknown-field", "pages-not-array",
            ),
            ::parseBatchRequest,
        )
    }

    @Test
    fun buildersProduceWhatTheContractAccepts() {
        for (name in fixtureNames("extraction-batch", "valid")) {
            val request = parseBatchRequest(extractionFixture("extraction-batch", "valid", name))
            val work = (parseClaim(extractionFixture("extraction-claim", "valid", "work-fresh")) as ClaimResponse.Work).work.copy(
                leaseToken = request.leaseToken,
                contentHash = request.contentHash,
                extractorVersion = request.extractorVersion,
                policyRevision = request.policyRevision,
                generationId = request.generationId,
            )
            assertEquals(name, request, parseBatchRequest(batchRequestBody(work, request.batch)))
        }
        val work = (parseClaim(extractionFixture("extraction-claim", "valid", "work-fresh")) as ClaimResponse.Work).work
        for (outcome in ExtractionOutcome.entries) assertEquals(outcome, parseOutcomeRequest(outcomeRequestBody(work, outcome)).outcome)
        val bad = ExtractionBatch(0, listOf(PageText(1, 0, PageMethod.OCR, "")), 1, false)
        try {
            parseBatchRequest(batchRequestBody(work, bad))
            fail("an empty page text must not be sendable")
        } catch (_: WireException) {
            // the builder's output is checked by the same parser the fixtures use
        }
    }

    @Test
    fun batchAnswers() {
        val valid = allValid(
            "extraction-batch-result",
            setOf("accepted-open", "replayed", "published-ready", "published-limited", "replayed-final"),
            ::parseBatchResult,
        )
        assertEquals(BatchStatus.ACCEPTED, valid.getValue("accepted-open").status)
        assertFalse(valid.getValue("accepted-open").published)
        assertEquals(BatchStatus.REPLAYED, valid.getValue("replayed").status)
        assertTrue(valid.getValue("published-ready").published)
        assertFalse(valid.getValue("published-ready").limited)
        assertTrue(valid.getValue("published-limited").limited)
        // A repeat of the final batch says it is published, with no attachment to describe.
        assertTrue(valid.getValue("replayed-final").published)
        assertFalse(valid.getValue("replayed-final").limited)
        allInvalid(
            "extraction-batch-result",
            setOf("published-without-attachment", "attachment-on-open-batch", "attachment-on-replay", "unknown-status", "attachment-failed", "missing-hash"),
            ::parseBatchResult,
        )
    }

    @Test
    fun outcomeRequests() {
        val valid = allValid("extraction-outcome", setOf("encrypted", "unavailable"), ::parseOutcomeRequest)
        assertEquals(ExtractionOutcome.ENCRYPTED, valid.getValue("encrypted").outcome)
        assertEquals(ExtractionOutcome.UNAVAILABLE, valid.getValue("unavailable").outcome)
        allInvalid("extraction-outcome", setOf("unknown-outcome", "missing-token", "unknown-field"), ::parseOutcomeRequest)
    }
}
