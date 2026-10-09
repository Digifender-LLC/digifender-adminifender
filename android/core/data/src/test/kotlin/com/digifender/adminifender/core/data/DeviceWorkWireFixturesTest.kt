package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.WireException
import com.digifender.adminifender.core.data.network.declineBody
import com.digifender.adminifender.core.data.network.parseDeclineRequest
import com.digifender.adminifender.core.data.network.parseDeviceClaim
import com.digifender.adminifender.core.data.network.parseReadiness
import com.digifender.adminifender.core.data.network.parseResultAnswer
import com.digifender.adminifender.core.data.network.parseResultRequest
import com.digifender.adminifender.core.data.network.readinessBody
import com.digifender.adminifender.core.data.network.resultBody
import com.digifender.adminifender.core.data.network.wireJson
import com.digifender.adminifender.core.model.Thermal
import java.io.File
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun deviceFixtureNames(contract: String, kind: String): Set<String> =
    File(System.getProperty("contractsDir") ?: error("contractsDir"), "fixtures/v1/$contract/$kind").listFiles().orEmpty().map { it.name.removeSuffix(".json") }.toSet()

private fun deviceFixture(contract: String, kind: String, name: String): JsonElement = wireJson.parseToJsonElement(contractFile("fixtures/v1/$contract/$kind/$name.json"))

/**
 * Every shared fixture of the device-work inference contracts is accounted for: each valid one parses, each invalid one is
 * refused with a [WireException], and a fixture added without saying what the phone does with it fails here.
 */
class DeviceWorkWireFixturesTest {
    private fun <T> allValid(contract: String, expected: Set<String>, parse: (JsonElement) -> T): Map<String, T> {
        assertEquals("$contract valid fixtures", expected, deviceFixtureNames(contract, "valid"))
        return expected.associateWith { parse(deviceFixture(contract, "valid", it)) }
    }

    private fun allInvalid(contract: String, expected: Set<String>, parse: (JsonElement) -> Any?) {
        assertEquals("$contract invalid fixtures", expected, deviceFixtureNames(contract, "invalid"))
        for (name in expected) {
            try {
                parse(deviceFixture(contract, "invalid", name))
                fail("$contract/$name should be refused")
            } catch (_: WireException) {
                // refused, as it must be
            }
        }
    }

    @Test
    fun readiness() {
        val valid = allValid("device-work-readiness", setOf("charging-background", "foreground-interactive", "no-model-loaded"), ::parseReadiness)
        assertTrue(valid.getValue("charging-background").charging)
        assertEquals(Thermal.NONE, valid.getValue("charging-background").thermal)
        assertTrue(valid.getValue("foreground-interactive").foreground)
        assertNull(valid.getValue("no-model-loaded").modelId)
        assertNull(valid.getValue("no-model-loaded").modelManifestHash)
        allInvalid("device-work-readiness", setOf("battery-over-100", "unknown-thermal", "model-without-hash-field", "unknown-field", "charging-not-boolean", "short-hash"), ::parseReadiness)
        // What the phone sends is what the contract accepts.
        for (readiness in valid.values) assertEquals(readiness, parseReadiness(readinessBody(readiness)))
    }

    @Test
    fun claims() {
        val valid = allValid("device-work-claim", setOf("triage", "chat-repair", "not-ready", "busy", "no-work"), ::parseDeviceClaim)
        val triage = valid.getValue("triage").work!!
        assertEquals("triage", triage.task)
        assertEquals(uuid(3), triage.sourceId)
        assertEquals(4, triage.sourceRevision)
        assertNull(triage.turnId)
        assertEquals(listOf("system", "user"), triage.messages.map { it.role })
        assertFalse(triage.repair)
        val chat = valid.getValue("chat-repair").work!!
        assertEquals("chat", chat.task)
        assertEquals(uuid(6), chat.turnId)
        assertNull(chat.sourceId)
        assertTrue(chat.repair)
        assertNull(chat.modelId)
        assertEquals("not_ready", valid.getValue("not-ready").reason)
        assertNull(valid.getValue("busy").work)
        allInvalid(
            "device-work-claim",
            setOf(
                "null-work-without-reason", "work-with-reason", "unknown-reason", "triage-without-source", "triage-with-turn", "chat-without-turn", "chat-with-source",
                "empty-messages", "unknown-role", "unknown-task", "missing-lease-token",
            ),
            ::parseDeviceClaim,
        )
    }

    @Test
    fun results() {
        val valid = allValid("device-work-result", setOf("output", "empty-output"), ::parseResultRequest)
        assertEquals("{\"concerns\":[]}", valid.getValue("output").outputJson)
        assertEquals("", valid.getValue("empty-output").outputJson)
        allInvalid("device-work-result", setOf("output-too-long", "missing-manifest-hash", "unknown-field", "negative-policy", "bad-token"), ::parseResultRequest)
        for (result in valid.values) assertEquals(result, parseResultRequest(resultBody(result)))
    }

    @Test
    fun resultAnswers() {
        val valid = allValid("device-work-result-answer", setOf("succeeded", "discarded", "invalid-output-with-repair"), ::parseResultAnswer)
        assertEquals("succeeded", valid.getValue("succeeded").state)
        assertEquals("discarded", valid.getValue("discarded").state)
        val invalid = valid.getValue("invalid-output-with-repair")
        assertEquals(listOf("evidence offset out of range"), invalid.errors)
        assertTrue(invalid.repairQueued)
        allInvalid("device-work-result-answer", setOf("succeeded-with-errors", "invalid-without-errors", "unknown-state", "missing-attempt"), ::parseResultAnswer)
    }

    @Test
    fun declines() {
        val valid = allValid("device-work-decline", setOf("too-large"), ::parseDeclineRequest)
        assertEquals(uuid(5) to "input_too_large", valid.getValue("too-large"))
        allInvalid("device-work-decline", setOf("other-reason", "missing-reason", "unknown-field"), ::parseDeclineRequest)
        assertEquals(valid.getValue("too-large"), parseDeclineRequest(declineBody(uuid(5), "input_too_large")))
    }
}
