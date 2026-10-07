package com.digifender.adminifender.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.digifender.adminifender.AppContainer
import com.digifender.adminifender.R
import com.digifender.adminifender.core.data.platform.CredentialManagerPasskeys
import com.digifender.adminifender.core.data.session.SessionState
import com.digifender.adminifender.core.data.chat.ChatScope
import com.digifender.adminifender.core.data.repository.ExportSaveOutcome
import com.digifender.adminifender.push.BRIEFING_DESTINATION
import com.digifender.adminifender.push.PushRegistrationWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val CLOCK_TICK_MS = 30_000L

private enum class Tab { INBOX, ARCHIVE, BRIEFING, CHAT, SETTINGS }

/** One open detail screen. The stack is saved as text: only IDs, never content (lld.md#android). */
internal sealed interface Route {
    val id: String

    data class ConcernRoute(override val id: String) : Route
    data class SourceRoute(override val id: String) : Route
    data class DeleteRoute(override val id: String) : Route
    data class ChatRoute(override val id: String, val kind: String) : Route
    data object ExportsRoute : Route { override val id = "" }
    data object PendingEditsRoute : Route { override val id = "" }
    data object HealthRoute : Route { override val id = "" }
}

internal fun encode(stack: List<Route>) = stack.takeLast(20).joinToString("|") {
    when (it) {
        is Route.ConcernRoute -> "c:${it.id}"
        is Route.SourceRoute -> "s:${it.id}"
        is Route.DeleteRoute -> "d:${it.id}"
        is Route.ChatRoute -> "h:${it.kind}:${it.id}"
        Route.ExportsRoute -> "e:"
        Route.PendingEditsRoute -> "p:"
        Route.HealthRoute -> "v:"
    }
}

private val ROUTE_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
internal fun decode(text: String): List<Route> = text.split("|").takeLast(20).mapNotNull { value ->
    val fields = value.split(":")
    when {
        value == "e:" -> Route.ExportsRoute
        value == "p:" -> Route.PendingEditsRoute
        value == "v:" -> Route.HealthRoute
        fields.size == 3 && fields[0] == "h" && fields[1] in setOf("source", "concern") && ROUTE_ID.matches(fields[2]) -> Route.ChatRoute(fields[2], fields[1])
        fields.size != 2 || !ROUTE_ID.matches(fields[1]) -> null
        fields[0] == "c" -> Route.ConcernRoute(fields[1])
        fields[0] == "s" -> Route.SourceRoute(fields[1])
        fields[0] == "d" -> Route.DeleteRoute(fields[1])
        else -> null
    }
}

@Composable
fun AppRoot(container: AppContainer, activity: Activity, destination: MutableStateFlow<String?>) {
    val sessionModel = viewModel { SessionViewModel(container.session, container::startSync, container::signOut, container::cleanupPending) }
    val session by sessionModel.state.collectAsStateWithLifecycle()
    val signingOut by container.signingOut.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Deliberately ephemeral: recreation forgets the selection and cleans a late new document, never
    // resuming a private copy from saved activity state. The callback stays registered across sign-out.
    var pendingExport by remember { mutableStateOf<Pair<ExportCopySelection, ExportViewModel>?>(null) }
    var partialCopyRetained by remember { mutableStateOf(false) }
    val saveExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val selected = pendingExport
        pendingExport = null
        val current = selected?.second?.state?.value
        val valid = selected?.first?.matches(current?.export?.id, container.privateSessionEpoch(),
            container.session.state.value is SessionState.SignedIn && !container.signingOut.value && !container.cleanupPending(),
            current?.busy ?: true) == true
        if (valid && selected != null) {
            selected.second.saveCopy { id ->
                if (selected.first.matches(id, container.privateSessionEpoch(),
                        container.session.state.value is SessionState.SignedIn && !container.signingOut.value && !container.cleanupPending(), false)) {
                    container.exportDocuments.write(selected.first.exportId, uri)
                } else container.exportDocuments.discardCreated(uri)
            }
        } else scope.launch {
            val discarded = container.exportDocuments.discardCreated(uri)
            partialCopyRetained = discarded is ExportSaveOutcome.Failed && !discarded.partialRemoved
        }
    }
    if (partialCopyRetained) {
        AlertDialog(onDismissRequest = { partialCopyRetained = false },
            text = { Text(stringResource(R.string.export_save_failed_partial)) },
            confirmButton = { TextButton(onClick = { partialCopyRetained = false }) { Text(stringResource(R.string.cancel)) } })
    }
    if (session.signOutFailed) {
        AlertDialog(onDismissRequest = sessionModel::dismissSignOutError,
            title = { Text(stringResource(R.string.sign_out_failed_title)) },
            text = { Text(stringResource(R.string.sign_out_failed_body)) },
            confirmButton = { TextButton(onClick = sessionModel::signOut, enabled = !session.busy) { Text(stringResource(R.string.sign_out_retry_cleanup)) } },
            dismissButton = { TextButton(onClick = sessionModel::dismissSignOutError) { Text(stringResource(R.string.cancel)) } })
    }
    if (signingOut) {
        Text(stringResource(R.string.signing_out), modifier = Modifier.padding(24.dp))
        return
    }
    when (session.session) {
        SessionState.Unknown -> if (session.message != null) SignInScreen(session) { sessionModel.signIn(CredentialManagerPasskeys(activity)) }
        SessionState.SignedOut -> SignInScreen(session) { sessionModel.signIn(CredentialManagerPasskeys(activity)) }
        // Cached data stays readable while the server wants a fresh sign-in (FR-002).
        SessionState.SignInRequired, is SessionState.SignedIn -> {
            // Every private screen model is disposed when sign-out removes this subtree, including cached
            // HTTP search results and rendered chat answers. Saved navigation contains IDs only.
            val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
            DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                SignedIn(container, activity, destination, needsSignIn = session.session == SessionState.SignInRequired,
                    busy = session.busy, onSignOut = sessionModel::signOut,
                    onSaveExport = { model ->
                        val state = model.state.value
                        val id = state.export?.id
                        if (id != null && state.saved && !state.busy && pendingExport == null) {
                            pendingExport = ExportCopySelection(id, container.privateSessionEpoch()) to model
                            saveExport.launch("adminifender-export.zip")
                        }
                    }, onLeaveExports = { pendingExport = null }) {
                    sessionModel.signIn(CredentialManagerPasskeys(activity))
                }
            }
        }
    }
}

@Composable
private fun SignedIn(container: AppContainer, activity: Activity, destination: MutableStateFlow<String?>, needsSignIn: Boolean, busy: Boolean, onSignOut: () -> Unit,
    onSaveExport: (ExportViewModel) -> Unit, onLeaveExports: () -> Unit, onSignIn: () -> Unit) {
    val inboxModel = viewModel { InboxViewModel(container.concerns) }
    val inbox by inboxModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
    // A notification tap names a concern; the detail screen reads it from the cache like any other open.
    val target by destination.collectAsStateWithLifecycle()
    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        tab = if (t == BRIEFING_DESTINATION) Tab.BRIEFING else Tab.INBOX
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
                    NavigationBarItem(selected = tab == Tab.BRIEFING, onClick = { tab = Tab.BRIEFING }, icon = {}, label = { Text(stringResource(R.string.nav_briefing)) })
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
                    TextButton(onClick = { open(Route.ChatRoute(id, "concern")) }, enabled = concern.view != null) { Text(stringResource(R.string.context_chat)) }
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
                    ConcernScreen(concern, now, concernModel::act, concernModel::reapply, concernModel::discard, sources, { open(Route.SourceRoute(it)) })
                }
                is Route.SourceRoute -> {
                    val id = route.id
                    val sourceModel = viewModel(key = "source-$id") { SourceViewModel(id, container.archive) }
                    val source by sourceModel.state.collectAsStateWithLifecycle()
                    val busy by sourceModel.busy.collectAsStateWithLifecycle()
                    val attachmentModel = viewModel(key = "attachments-$id") { AttachmentViewModel(id, container.attachments) }
                    val attachments by attachmentModel.state.collectAsStateWithLifecycle()
                    TextButton(onClick = ::back, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.back)) }
                    TextButton(onClick = { open(Route.ChatRoute(id, "source")) }, enabled = source.summary != null) { Text(stringResource(R.string.context_chat)) }
                    SourceScreen(source, busy, sourceModel::download, { open(Route.ConcernRoute(it)) },
                        attachmentState = attachments, onDownloadAttachment = attachmentModel::download,
                        onRemoveAttachment = attachmentModel::remove, onDelete = { open(Route.DeleteRoute(id)) })
                }
                is Route.DeleteRoute -> {
                    val deletionModel = viewModel(key = "delete-${route.id}") { DeletionViewModel(listOf(route.id), container.reviews, container.session::confirmRecent) }
                    val deletion by deletionModel.state.collectAsStateWithLifecycle()
                    TextButton(onClick = ::back) { Text(stringResource(R.string.back)) }
                    DeletionReviewScreen(deletion, now, { deletionModel.confirm(CredentialManagerPasskeys(activity)) }, deletionModel::prepare)
                }
                is Route.ChatRoute -> {
                    TextButton(onClick = ::back) { Text(stringResource(R.string.back)) }
                    ChatRoute(container.chat, ChatScope(route.kind, route.id), inbox.freshness?.lastSyncedAtMs) { open(Route.SourceRoute(it)) }
                }
                Route.ExportsRoute -> {
                    val exportModel = viewModel { ExportViewModel(container.exports) }
                    val export by exportModel.state.collectAsStateWithLifecycle()
                    DisposableEffect(exportModel) { onDispose { onLeaveExports() } }
                    TextButton(onClick = ::back) { Text(stringResource(R.string.back)) }
                    ExportScreen(export, now, exportModel::create, exportModel::refresh, exportModel::download, exportModel::remove, exportModel::newExport,
                        onSaveCopy = { onSaveExport(exportModel) })
                }
                Route.PendingEditsRoute -> {
                    val edits by remember { container.concerns.pendingEdits() }.collectAsStateWithLifecycle(emptyList())
                    TextButton(onClick = ::back) { Text(stringResource(R.string.back)) }
                    PendingEditsScreen(edits, { scope.launch { container.concerns.reapply(it) } }, { scope.launch { container.concerns.discard(it) } })
                }
                Route.HealthRoute -> {
                    TextButton(onClick = ::back) { Text(stringResource(R.string.back)) }
                    HealthRoute(container.health, container.healthProbe, inbox.freshness)
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
                    Tab.BRIEFING -> {
                        val briefingModel = viewModel { BriefingViewModel(container.briefings) }
                        val briefing by briefingModel.state.collectAsStateWithLifecycle()
                        BriefingScreen(briefing, briefingModel::refresh, briefingModel::review,
                            { open(Route.ConcernRoute(it)) }, { open(Route.SourceRoute(it)) })
                    }
                    Tab.CHAT -> ChatRoute(container.chat, refreshKey = inbox.freshness?.lastSyncedAtMs) { open(Route.SourceRoute(it)) }
                    Tab.SETTINGS -> SettingsScreen(inbox.freshness, now, inboxModel::refresh, onSignOut,
                        onNotificationsChanged = { PushRegistrationWorker.enqueue(context) },
                        onExports = { open(Route.ExportsRoute) }, onPendingEdits = { open(Route.PendingEditsRoute) },
                        onHealth = { open(Route.HealthRoute) })
                }
            }
        }
    }
}
