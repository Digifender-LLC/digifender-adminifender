package com.digifender.adminifender.ui.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.digifender.adminifender.core.data.network.ArchiveView
import com.digifender.adminifender.core.data.network.CitationDto
import com.digifender.adminifender.core.data.network.ClaimDto
import com.digifender.adminifender.core.data.network.ReviewDto
import com.digifender.adminifender.core.data.repository.ChatScope
import com.digifender.adminifender.core.data.repository.ChatTurn
import com.digifender.adminifender.core.data.repository.ConcernView
import com.digifender.adminifender.core.data.repository.Freshness
import com.digifender.adminifender.core.data.repository.InboxFilter
import com.digifender.adminifender.core.data.repository.TurnState
import com.digifender.adminifender.core.model.Concern
import com.digifender.adminifender.core.model.Due
import com.digifender.adminifender.core.model.Importance
import com.digifender.adminifender.core.model.Status
import com.digifender.adminifender.core.model.Waiting
import com.digifender.adminifender.ui.ChatScreen
import com.digifender.adminifender.ui.ChatUiState
import com.digifender.adminifender.ui.InboxScreen
import com.digifender.adminifender.ui.InboxUiState
import com.digifender.adminifender.ui.ReviewNote
import com.digifender.adminifender.ui.ReviewPanel
import com.digifender.adminifender.ui.ReviewUiState
import com.digifender.adminifender.ui.theme.AdminifenderTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Synthetic data only. Renders screens at phone size so the design can be inspected without a device. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A plain Application: screenshots need no sync, push or WorkManager wiring.
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xhdpi", application = android.app.Application::class)
class ScreensScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val now = System.currentTimeMillis()
    private val day = 24L * 60 * 60 * 1000

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { AdminifenderTheme { Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() } } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun concern(id: String, title: String, status: Status = Status.OPEN, dueAt: Long? = null, waitingOn: String? = null, importance: Importance = Importance.IMPORTANT, read: Boolean = true) = Concern(
        id = id, revision = 1, title = title, status = status, importance = importance, criticalBasis = null,
        readAtMs = if (read) now else null, acknowledgedAtMs = null, resolvedAtMs = null, snoozeUntilMs = null,
        waiting = if (status == Status.WAITING) Waiting(null, waitingOn, null) else null,
        due = dueAt?.let { Due(it, "UTC", null, 1) }, sourceIds = listOf("s"), updatedAtMs = now,
    )

    private fun view(c: Concern) = ConcernView(c, c, 0, emptyList(), emptyList(), false)

    @Test fun attention() = attentionAt("attention")

    private fun attentionAt(name: String) = shot(name) {
        val items = listOf(
            concern("1", "Harbor Insurance premium", dueAt = now - 2 * day, importance = Importance.URGENT),
            concern("2", "Riverside Water statement", dueAt = now + 4 * day, read = false),
            concern("3", "Deposit refund, Lakeview Storage", Status.WAITING, waitingOn = "Lakeview Storage", importance = Importance.ROUTINE),
            concern("4", "Domain renewal", dueAt = now + 17 * day),
        ).map(::view)
        InboxScreen(InboxUiState(InboxFilter.ACTIVE, items, Freshness(now - 6 * 60_000, false, 0, 0, false)), now, {}, {}, {})
    }

    /** Large system text (1.5x): titles and pills must wrap, not clip. */
    @Test fun attentionLargeText() {
        RuntimeEnvironment.setFontScale(1.5f)
        attentionAt("attention-large-text")
    }

    @Test fun askLargeText() {
        RuntimeEnvironment.setFontScale(1.5f)
        ask("ask-large-text")
    }

    @Test fun ask() = ask("ask")

    private fun ask(name: String) = shot(name) {
        val c1 = CitationDto(chunkId = "s1:body-v1:0", sourceId = "s1", start = 0, end = 40, excerpt = "Amount due \$62.15 by October 9.")
        val c2 = CitationDto(chunkId = "s2:body-v1:0", sourceId = "s2", start = 0, end = 30, excerpt = "Amount due by September 9.")
        val turn = ChatTurn(
            turnId = "t", threadId = "th", content = "When is the water bill due, and did I pay last month's?", state = TurnState.COMPLETE, sent = true,
            reason = null, errorCode = null,
            answer = "The October statement is \$62.15, due October 9. I found no payment confirmation for September.",
            claims = listOf(ClaimDto("The October statement is \$62.15, due October 9.", "evidence", listOf(c1)), ClaimDto("No email confirms the September payment.", "inference", listOf(c2))),
            gaps = listOf("No payment receipt for September in the archive."), invalidated = false, withheld = false, createdAtMs = now, completedAtMs = now,
        )
        ChatScreen(ChatUiState(ChatScope.ALL, "th", false, listOf(turn), null), {}, { true }, {}, {}, {}, {})
    }

    private val review = ReviewDto(
        reviewId = "r", effect = "archive.delete", paramsDigest = "d",
        preview = Json.parseToJsonElement("""{"sources":[{"subject":"Harbor Insurance, Oct 2"}],"attachments":1,"concernsErased":[],"concernsDetached":["c1"]}"""),
        expiresAt = "2026-10-04T18:00:00Z",
    )

    @Test fun confirm() = shot("confirm") { Box(Modifier.padding(16.dp)) { ReviewPanel(ReviewUiState.Ready(review), {}, {}, {}) } }

    @Test fun uncertain() = shot("uncertain") { Box(Modifier.padding(16.dp)) { ReviewPanel(ReviewUiState.Ready(review, note = ReviewNote.UNKNOWN), {}, {}, {}) } }
}
