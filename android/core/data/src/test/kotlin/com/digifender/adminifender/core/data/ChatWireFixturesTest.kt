package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.ChatScope
import com.digifender.adminifender.core.data.network.HistoryDto
import com.digifender.adminifender.core.data.network.ScopeKind
import com.digifender.adminifender.core.data.network.ThreadDto
import com.digifender.adminifender.core.data.network.TurnDto
import com.digifender.adminifender.core.data.network.wireJson
import com.digifender.adminifender.core.data.repository.AnswerProblem
import com.digifender.adminifender.core.data.repository.ClaimBasis
import com.digifender.adminifender.core.data.repository.ContextSource
import com.digifender.adminifender.core.data.repository.ServerState
import com.digifender.adminifender.core.data.repository.ServerTurn
import com.digifender.adminifender.core.data.repository.toServerTurn
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CASE_SOURCE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

private fun fixtureNames(contract: String, kind: String): Set<String> =
    File(System.getProperty("contractsDir") ?: error("contractsDir"), "fixtures/v1/$contract/$kind").listFiles().orEmpty().map { it.name.removeSuffix(".json") }.toSet()

private fun turnFixture(kind: String, name: String): TurnDto = wireJson.decodeFromString(TurnDto.serializer(), contractFile("fixtures/v1/chat-turn/$kind/$name.json"))

/**
 * The shared synthetic turn, thread and history fixtures (contracts/fixtures/v1/chat-*), which the Worker's tests
 * hold to the same shapes. Every fixture is accounted for: adding one without saying what the phone does with it fails.
 */
class ChatWireFixturesTest {
    private sealed interface Outcome {
        /** The turn reads, and the answer is usable. */
        data object Usable : Outcome

        /** The turn reads, but the answer is unusable for this reason and nothing of it is kept. */
        data class Unusable(val problem: AnswerProblem) : Outcome

        /** There is no answer to show: the extra or stray fields on the wire are not kept. */
        data object NoAnswer : Outcome

        /** The phone cannot read the turn at all (an unknown state or claim basis). */
        data object Unreadable : Outcome
    }

    private fun outcome(dto: TurnDto): Outcome {
        val turn = try {
            dto.toServerTurn()
        } catch (_: IllegalArgumentException) {
            return Outcome.Unreadable
        }
        return when {
            turn.answerProblem != null -> Outcome.Unusable(turn.answerProblem)
            turn.answer != null -> Outcome.Usable
            else -> Outcome.NoAnswer
        }
    }

    @Test
    fun everyValidTurnFixtureReadsAndTheAnswersCarryTheirContext() {
        val names = fixtureNames("chat-turn", "valid")
        assertEquals(setOf("pending-local-only", "running", "complete-cited", "complete-no-evidence", "withheld", "invalidated", "failed-invalid-output", "uncertain", "cancelled"), names)
        for (name in names) {
            val turn = turnFixture("valid", name).toServerTurn()
            assertNull(name, turn.answerProblem)
            assertEquals(name, name == "complete-cited" || name == "complete-no-evidence", turn.answer != null)
        }
        val cited = turnFixture("valid", "complete-cited").toServerTurn()
        assertEquals(ServerState.COMPLETE, cited.state)
        assertEquals(listOf(ClaimBasis.EVIDENCE, ClaimBasis.REPORT, ClaimBasis.HYPOTHESIS), cited.answer?.claims?.map { it.basis })
        // The model was given two emails but cited one; both are in the context the phone checks.
        assertEquals(listOf(ContextSource("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "hybrid"), ContextSource("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "local_only")), cited.answer?.context)
        assertEquals("email.reply", cited.answer?.proposals?.single()?.kind)
        val attachment = cited.answer?.claims?.get(1)?.citations?.single()
        assertEquals(2, attachment?.page)
        assertNotNull(attachment?.attachmentId)
        assertEquals(emptyList<ContextSource>(), turnFixture("valid", "complete-no-evidence").toServerTurn().answer?.context)
        assertTrue(turnFixture("valid", "withheld").toServerTurn().withheld)
        assertTrue(turnFixture("valid", "invalidated").toServerTurn().invalidated)
        assertEquals("local_only", turnFixture("valid", "pending-local-only").toServerTurn().reason)
    }

    @Test
    fun everyInvalidTurnFixtureIsRefusedOrNothingOfItIsKept() {
        val expected = mapOf(
            // The Worker never sends an answer without its context; if it did, the phone holds it back and reads again.
            "answer-without-context" to Outcome.Unusable(AnswerProblem.CONTEXT_UNKNOWN),
            "evidence-without-citation" to Outcome.Unusable(AnswerProblem.MALFORMED),
            "report-without-citation" to Outcome.Unusable(AnswerProblem.MALFORMED),
            "unsupported-proposal-kind" to Outcome.Unusable(AnswerProblem.MALFORMED),
            "arbitrary-proposal-target" to Outcome.Unusable(AnswerProblem.MALFORMED),
            "context-privacy-no-ai" to Outcome.Unusable(AnswerProblem.MALFORMED),
            "citation-without-source" to Outcome.Unusable(AnswerProblem.MALFORMED),
            "unknown-basis" to Outcome.Unreadable,
            "unknown-state" to Outcome.Unreadable,
            // Stray fields are dropped, never kept or acted on; the turn is otherwise the valid one.
            "tool-call-field" to Outcome.Usable,
            "context-without-answer" to Outcome.NoAnswer,
            "claims-without-answer" to Outcome.NoAnswer,
        )
        assertEquals(expected.keys, fixtureNames("chat-turn", "invalid"))
        for ((name, want) in expected) assertEquals(name, want, outcome(turnFixture("invalid", name)))
    }

    @Test
    fun anAnswerWhoseProposalOrCitationNamesAnEmailOutsideItsContextIsUnusable() {
        val valid = turnFixture("valid", "complete-cited")
        val outsideContext = valid.copy(context = valid.context!!.filter { it.sourceId != "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb" })
        assertEquals(Outcome.Unusable(AnswerProblem.MALFORMED), outcome(outsideContext))
        val uncitedSource = valid.copy(context = valid.context.filter { it.sourceId != CASE_SOURCE })
        assertEquals(Outcome.Unusable(AnswerProblem.MALFORMED), outcome(uncitedSource))
        assertEquals(Outcome.Usable, outcome(valid))
    }

    @Test
    fun theLimitsOfTheSchemaAreEnforcedOnThePhoneToo() {
        val valid = turnFixture("valid", "complete-cited")
        val claim = valid.claims.first()
        val citation = claim.citations.first()
        fun unusable(dto: TurnDto) = assertEquals(Outcome.Unusable(AnswerProblem.MALFORMED), outcome(dto))
        unusable(valid.copy(answer = "x".repeat(4001)))
        unusable(valid.copy(answer = ""))
        unusable(valid.copy(claims = List(11) { claim }))
        unusable(valid.copy(claims = listOf(claim.copy(text = ""))))
        unusable(valid.copy(claims = listOf(claim.copy(text = "x".repeat(1001)))))
        unusable(valid.copy(claims = listOf(claim.copy(citations = List(5) { citation }))))
        unusable(valid.copy(gaps = List(6) { "gap" }))
        unusable(valid.copy(gaps = listOf("x".repeat(301))))
        unusable(valid.copy(proposals = List(4) { valid.proposals.first() }))
        unusable(valid.copy(proposals = listOf(valid.proposals.first().copy(description = ""))))
        unusable(valid.copy(proposals = listOf(valid.proposals.first().copy(description = "x".repeat(501)))))
        for (bad in listOf(citation.copy(chunkId = "has space"), citation.copy(chunkId = ""), citation.copy(start = -1), citation.copy(start = 5, end = 5), citation.copy(start = 6, end = 3), citation.copy(end = 6001), citation.copy(page = -1), citation.copy(attachmentId = "not-an-id"))) {
            unusable(valid.copy(claims = listOf(claim.copy(citations = listOf(bad)))))
        }
        unusable(valid.copy(context = List(5) { valid.context!!.first() }))
        unusable(valid.copy(context = listOf(valid.context!!.first().copy(sourceId = "nope"))))
    }

    @Test
    fun aClaimWithAnUnknownBasisFailsTheWholeReadSoANewerWorkerIsNeverHalfUnderstood() {
        val valid = turnFixture("valid", "complete-cited")
        assertEquals(Outcome.Unreadable, outcome(valid.copy(claims = listOf(valid.claims.first().copy(basis = "certain")))))
        assertEquals(Outcome.Unreadable, outcome(valid.copy(state = "streaming")))
        assertEquals(Outcome.Unreadable, outcome(valid.copy(scope = valid.scope.copy(kind = "mailbox"))))
        assertEquals(Outcome.Unreadable, outcome(valid.copy(scope = valid.scope.copy(kind = "all", id = CASE_SOURCE))))
    }

    @Test
    fun threadAndHistoryFixturesReadThroughTheSameDtos() {
        assertEquals(setOf("all-scope", "concern-scope"), fixtureNames("chat-thread", "valid"))
        for (name in fixtureNames("chat-thread", "valid")) {
            val thread = wireJson.decodeFromString(ThreadDto.serializer(), contractFile("fixtures/v1/chat-thread/valid/$name.json"))
            assertEquals("7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f", thread.id)
        }
        assertEquals(setOf("all-with-id", "source-without-id", "unknown-kind"), fixtureNames("chat-thread", "invalid"))
        // The phone checks the shape a thread's scope must have before it adopts the thread (ChatScope.wellFormed).
        for (name in fixtureNames("chat-thread", "invalid")) {
            val thread = wireJson.decodeFromString(ThreadDto.serializer(), contractFile("fixtures/v1/chat-thread/invalid/$name.json"))
            val kind = ScopeKind.fromWire(thread.scope.kind)
            val adoptable = kind != null && ChatScope(kind, thread.scope.id).wellFormed
            assertFalse(name, adoptable)
        }

        assertEquals(setOf("two-turns-more-before", "empty"), fixtureNames("chat-history", "valid"))
        val page = wireJson.decodeFromString(HistoryDto.serializer(), contractFile("fixtures/v1/chat-history/valid/two-turns-more-before.json"))
        assertTrue(page.hasMore)
        val turns: List<ServerTurn> = page.turns.map { it.toServerTurn() }
        assertEquals(2, turns.size)
        assertTrue(turns[0].createdAtMs < turns[1].createdAtMs)
        assertEquals("rests on nothing", emptyList<ContextSource>(), turns[1].answer?.context)
        assertTrue(wireJson.decodeFromString(HistoryDto.serializer(), contractFile("fixtures/v1/chat-history/valid/empty.json")).turns.isEmpty())

        assertEquals("the cursor is the oldest turn of the page", page.turns.first().turnId, page.nextBefore)
        assertNull(wireJson.decodeFromString(HistoryDto.serializer(), contractFile("fixtures/v1/chat-history/valid/empty.json")).nextBefore)

        assertEquals(
            setOf("missing-has-more", "turn-with-unknown-basis", "scope-all-with-id", "missing-next-before", "more-without-next-before", "next-before-without-more"),
            fixtureNames("chat-history", "invalid"),
        )
        // The three that break the cursor contract still decode; the phone refuses them when it reads the page
        // (ChatRepositoryTest.anEarlierPageThatCannotMakeProgressIsNotAdoptedAndAnEmptyOneEndsTheHistory), so no cursor is derived.
        for (name in listOf("missing-next-before", "more-without-next-before")) {
            val broken = wireJson.decodeFromString(HistoryDto.serializer(), contractFile("fixtures/v1/chat-history/invalid/$name.json"))
            assertTrue(name, broken.hasMore && broken.nextBefore == null)
        }
        val orphan = wireJson.decodeFromString(HistoryDto.serializer(), contractFile("fixtures/v1/chat-history/invalid/next-before-without-more.json"))
        assertTrue(!orphan.hasMore && orphan.nextBefore != null)
        val noFlag = runCatching { wireJson.decodeFromString(HistoryDto.serializer(), contractFile("fixtures/v1/chat-history/invalid/missing-has-more.json")) }
        assertTrue("a page without hasMore must not be adopted", noFlag.isFailure)
        val badBasis = wireJson.decodeFromString(HistoryDto.serializer(), contractFile("fixtures/v1/chat-history/invalid/turn-with-unknown-basis.json"))
        assertTrue(runCatching { badBasis.turns.map { it.toServerTurn() } }.isFailure)
        val scopeWithId = wireJson.decodeFromString(HistoryDto.serializer(), contractFile("fixtures/v1/chat-history/invalid/scope-all-with-id.json"))
        assertFalse(ChatScope(ScopeKind.ALL, scopeWithId.thread.scope.id).wellFormed)
    }

    @Test
    fun theModelAnswerFixturesBecomeTurnsTheAppCanShowOrRefuse() {
        val reply = "6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f"
        fun turnFrom(kind: String, name: String): TurnDto {
            val answer = wireJson.parseToJsonElement(contractFile("fixtures/v1/chat-answer/$kind/$name.json")).jsonObject
            fun copyOf(key: String): JsonElement = answer[key] ?: JsonArray(emptyList())
            // The Worker adds, for each citation, the email it names; the context lists every email the model had.
            val claims = JsonArray(
                (copyOf("claims") as JsonArray).map { claim ->
                    val fields = claim.jsonObject.toMutableMap()
                    fields["citations"] = JsonArray(
                        (claim.jsonObject["citations"] as? JsonArray).orEmpty().map { c ->
                            JsonObject(c.jsonObject + mapOf("sourceId" to JsonPrimitive(CASE_SOURCE)))
                        },
                    )
                    JsonObject(fields)
                },
            )
            val body = buildJsonObject {
                put("turnId", "11111111-1111-4111-8111-111111111111")
                put("threadId", "7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f")
                put("scope", buildJsonObject { put("kind", "all") })
                put("content", "q")
                put("state", "complete")
                put("answer", answer["answer"] ?: JsonPrimitive("x"))
                put("claims", claims)
                put("gaps", copyOf("gaps"))
                put("proposals", copyOf("proposals"))
                put("context", JsonArray(listOf(CASE_SOURCE, reply).map { buildJsonObject { put("sourceId", it); put("privacy", "hybrid") } }))
                put("createdAt", "2026-10-08T10:00:00.000Z")
            }
            return wireJson.decodeFromJsonElement(TurnDto.serializer(), body)
        }
        assertEquals(setOf("cited-with-gap", "reply-proposal"), fixtureNames("chat-answer", "valid"))
        for (name in fixtureNames("chat-answer", "valid")) assertEquals(name, Outcome.Usable, outcome(turnFrom("valid", name)))
        val expected = mapOf(
            "unknown-basis" to Outcome.Unreadable,
            "uncited-evidence" to Outcome.Unusable(AnswerProblem.MALFORMED),
            // shell.exec is not a proposal the phone accepts, and its target is not an email in the context.
            "arbitrary-action" to Outcome.Unusable(AnswerProblem.MALFORMED),
            // A stray `tool` field has no place on the phone; what is left is an ordinary text answer with no suggestion.
            "tool-call-field" to Outcome.Usable,
        )
        assertEquals(expected.keys, fixtureNames("chat-answer", "invalid"))
        for ((name, want) in expected) assertEquals(name, want, outcome(turnFrom("invalid", name)))
        val plain = turnFrom("invalid", "tool-call-field").toServerTurn().answer
        assertEquals("Fetching that page.", plain?.text)
        assertTrue(plain?.proposals.orEmpty().isEmpty())
    }

    @Test
    fun anOlderWorkerThatSendsAnAnswerWithoutContextIsHeldBackNotBelieved() {
        val json = contractFile("fixtures/v1/chat-turn/valid/complete-cited.json")
        val without = wireJson.parseToJsonElement(json).jsonObject.let { JsonObject(it - "context") }
        val dto = wireJson.decodeFromJsonElement(TurnDto.serializer(), without)
        assertNull(dto.context)
        assertEquals(Outcome.Unusable(AnswerProblem.CONTEXT_UNKNOWN), outcome(dto))
    }
}
