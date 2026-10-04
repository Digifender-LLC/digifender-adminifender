package com.digifender.adminifender.ui

import kotlinx.coroutines.launch
import com.digifender.adminifender.inference.ModelState
import com.digifender.adminifender.core.data.repository.ChatScope
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.digifender.adminifender.AppContainer
import com.digifender.adminifender.R
import com.digifender.adminifender.core.data.platform.CredentialManagerPasskeys
import com.digifender.adminifender.core.data.session.SessionState
import com.digifender.adminifender.push.BRIEFING_DESTINATION
import com.digifender.adminifender.push.PushRegistrationWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

private const val CLOCK_TICK_MS = 30_000L

private enum class Tab { INBOX, ARCHIVE, CHAT, SETTINGS }

/** One open detail screen. The stack is saved as text: only IDs, never content (lld.md#android). */
internal sealed interface Route {
    data class ConcernRoute(val id: String) : Route
    data class SourceRoute(val id: String) : Route
    data class ChatRoute(val scope: ChatScope) : Route
}

private val ROUTE_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

internal fun encode(stack: List<Route>) = stack.joinToString("|") {
    when (it) {
        is Route.ConcernRoute -> "c:${it.id}"
        is Route.SourceRoute -> "s:${it.id}"
        is Route.ChatRoute -> "t:${it.scope.kind.wire}:${it.scope.id ?: ""}"
    }
}

/** Restores a saved stack; anything that is not a known kind with a well-formed ID is dropped. */
internal fun decode(text: String): List<Route> = text.split("|").mapNotNull { entry ->
    val parts = entry.split(":")
    when {
        parts.size == 2 && parts[0] == "c" && ROUTE_ID.matches(parts[1]) -> Route.ConcernRoute(parts[1])
        parts.size == 2 && parts[0] == "s" && ROUTE_ID.matches(parts[1]) -> Route.SourceRoute(parts[1])
        parts == listOf("t", "all", "") -> Route.ChatRoute(ChatScope.ALL)
        parts.size == 3 && parts[0] == "t" && ROUTE_ID.matches(parts[2]) -> when (parts[1]) {
            "concern" -> Route.ChatRoute(ChatScope(ChatScope.Kind.CONCERN, parts[2]))
            "source" -> Route.ChatRoute(ChatScope(ChatScope.Kind.SOURCE, parts[2]))
            else -> null
        }
        else -> null
    }
}

@Composable
fun AppRoot(container: AppContainer, activity: Activity, destination: MutableStateFlow<String?>) {
    val sessionModel = viewModel { SessionViewModel(container.session, container::startSync, container::signOut) }
    val session by sessionModel.state.collectAsStateWithLifecycle()
    when (session.session) {
        SessionState.Unknown -> Unit
        SessionState.SignedOut -> SignInScreen(session) { sessionModel.signIn(CredentialManagerPasskeys(activity)) }
        // Cached data stays readable while the server wants a fresh sign-in (FR-002).
        SessionState.SignInRequired, is SessionState.SignedIn -> SignedIn(container, destination, needsSignIn = session.session == SessionState.SignInRequired, busy = session.busy) {
            sessionModel.signIn(CredentialManagerPasskeys(activity))
        }
    }
}

@Composable
private fun SignedIn(container: AppContainer, destination: MutableStateFlow<String?>, needsSignIn: Boolean, busy: Boolean, onSignIn: () -> Unit) {
    // Same instance as in AppRoot: one SessionViewModel per activity.
    val sessionModel = viewModel { SessionViewModel(container.session, container::startSync, container::signOut) }
    val inboxModel = viewModel { InboxViewModel(container.concerns) }
    val inbox by inboxModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.INBOX) }
    var saved by rememberSaveable { mutableStateOf("") }
    val stack = decode(saved)
    fun open(route: Route) {
        saved = encode(stack + route)
    }
    fun back() {
        saved = encode(stack.dropLast(1))
    }
    var now by remember { mutableLongStateOf(container.clock()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_TICK_MS)
            now = container.clock()
        }
    }
    BackHandler(enabled = stack.isNotEmpty()) { back() }
    val scope = rememberCoroutineScope()
    val modelState by container.models.state.collectAsStateWithLifecycle()
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { if (container.models.install(uri) is ModelState.Installed) container.startSync() }
    }
    val modelUi = ModelUi(modelState, { pickModel.launch(arrayOf("application/octet-stream", "*/*")) }, { scope.launch { container.models.remove() } })
    // A notification tap names a concern; the detail screen reads it from the cache like any other open.
    val target by destination.collectAsStateWithLifecycle()
    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        tab = Tab.INBOX
        saved = if (t == BRIEFING_DESTINATION) "" else encode(listOf(Route.ConcernRoute(t)))
        destination.value = null
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (stack.isEmpty()) {
                NavigationBar {
                    NavigationBarItem(selected = tab == Tab.INBOX, onClick = { tab = Tab.INBOX }, icon = {}, label = { Text(stringResource(R.string.nav_inbox)) })
                    NavigationBarItem(selected = tab == Tab.ARCHIVE, onClick = { tab = Tab.ARCHIVE }, icon = {}, label = { Text(stringResource(R.string.nav_archive)) })
                    NavigationBarItem(selected = tab == Tab.CHAT, onClick = { tab = Tab.CHAT }, icon = {}, label = { Text(stringResource(R.string.nav_chat)) })
                    NavigationBarItem(selected = tab == Tab.SETTINGS, onClick = { tab = Tab.SETTINGS }, icon = {}, label = { Text(stringResource(R.string.nav_settings)) })
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (needsSignIn) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.padding(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.sign_in_again_banner))
                        TextButton(onClick = onSignIn, enabled = !busy) { Text(stringResource(R.string.sign_in_again_button)) }
                    }
                }
            }
            when (val route = stack.lastOrNull()) {
                is Route.ConcernRoute -> {
                    val id = route.id
                    val concernModel = viewModel(key = "concern-$id") { ConcernViewModel(id, container.concerns) }
                    val concern by concernModel.state.collectAsStateWithLifecycle()
                    val message by concernModel.message.collectAsStateWithLifecycle()
                    val sourceIds = concern.view?.shown?.sourceIds.orEmpty()
                    val sources by remember(sourceIds) { container.archive.sources(sourceIds) }.collectAsStateWithLifecycle(emptyList())
                    TextButton(onClick = ::back, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.back)) }
                    message?.let {
                        Text(
                            stringResource(
                                when (it) {
                                    ActMessage.QUEUED -> R.string.act_queued
                                    ActMessage.NOT_AVAILABLE -> R.string.act_not_available
                                    ActMessage.SNOOZE_IN_PAST -> R.string.act_snooze_past
                                    ActMessage.GONE -> R.string.act_gone
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    ConcernScreen(concern, now, concernModel::act, concernModel::reapply, concernModel::discard, sources, { open(Route.SourceRoute(it)) }, { open(Route.ChatRoute(ChatScope(ChatScope.Kind.CONCERN, id))) })
                }
                is Route.SourceRoute -> {
                    val id = route.id
                    val sourceModel = viewModel(key = "source-$id") { SourceViewModel(id, container.archive) }
                    val source by sourceModel.state.collectAsStateWithLifecycle()
                    val busy by sourceModel.busy.collectAsStateWithLifecycle()
                    TextButton(onClick = ::back, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.back)) }
                    SourceScreen(
                        source,
                        busy,
                        sourceModel::download,
                        { open(Route.ConcernRoute(it)) },
                        { open(Route.ChatRoute(ChatScope(ChatScope.Kind.SOURCE, id))) },
                        { attachment -> scope.launch { container.resumeExtraction(attachment) }.invokeOnCompletion { sourceModel.download() } },
                    )
                }
                is Route.ChatRoute -> {
                    TextButton(onClick = ::back, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.back)) }
                    Chat(container, route.scope) { open(Route.SourceRoute(it)) }
                }
                null -> when (tab) {
                    Tab.INBOX -> InboxScreen(inbox, now, inboxModel::select, inboxModel::refresh, { open(Route.ConcernRoute(it)) })
                    Tab.ARCHIVE -> {
                        val archiveModel = viewModel { ArchiveViewModel(container.archive, container.clock) }
                        val archive by archiveModel.state.collectAsStateWithLifecycle()
                        val query by archiveModel.query.collectAsStateWithLifecycle()
                        val sender by archiveModel.sender.collectAsStateWithLifecycle()
                        val range by archiveModel.range.collectAsStateWithLifecycle()
                        ArchiveScreen(
                            archive,
                            SearchFields(query, sender, range),
                            onQuery = { archiveModel.query.value = it },
                            onSender = { archiveModel.sender.value = it },
                            onRange = { archiveModel.range.value = it },
                            onView = archiveModel::selectView,
                            onSubmit = archiveModel::submit,
                            onClear = archiveModel::clearSearch,
                            onMoreResults = archiveModel::loadMore,
                            onMoreList = archiveModel::showMore,
                            onOpen = { open(Route.SourceRoute(it)) },
                        )
                    }
                    Tab.CHAT -> Chat(container, ChatScope.ALL) { open(Route.SourceRoute(it)) }
                    Tab.SETTINGS -> SettingsScreen(inbox.freshness, now, inboxModel::refresh, sessionModel::signOut, onNotificationsChanged = { PushRegistrationWorker.enqueue(context) }, model = modelUi)
                }
            }
        }
    }
}

@Composable
private fun Chat(container: AppContainer, scope: ChatScope, onSource: (String) -> Unit) {
    val chatModel = viewModel(key = "chat-${scope.kind.wire}-${scope.id}") { ChatViewModel(scope, container.chat, container::runLocalChatNow) }
    val chat by chatModel.state.collectAsStateWithLifecycle()
    ChatScreen(chat, chatModel::open, chatModel::ask, chatModel::resend, chatModel::checkAgain, chatModel::cancel, onSource)
}
