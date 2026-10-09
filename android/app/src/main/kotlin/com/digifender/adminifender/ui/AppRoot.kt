package com.digifender.adminifender.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.digifender.adminifender.AppContainer
import com.digifender.adminifender.R
import com.digifender.adminifender.core.data.network.ChatScope
import com.digifender.adminifender.core.data.platform.CredentialManagerPasskeys
import com.digifender.adminifender.SafDestination
import com.digifender.adminifender.core.data.repository.ChatUnsent
import com.digifender.adminifender.core.data.review.DeletionPending
import com.digifender.adminifender.core.data.session.SessionState
import com.digifender.adminifender.push.BRIEFING_DESTINATION
import com.digifender.adminifender.push.PushRegistrationWorker
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val CLOCK_TICK_MS = 30_000L

private enum class Tab { INBOX, BRIEFING, ARCHIVE, CHAT, SETTINGS }

/** One open detail screen. The stack is saved as text: only IDs, never content (lld.md#android). */
private sealed interface Route {
    val id: String

    data class ConcernRoute(override val id: String) : Route
    data class SourceRoute(override val id: String) : Route

    /** [id] is the conversation's local ID; the Worker's thread ID is looked up from Room. */
    data class ChatRoute(override val id: String) : Route

    /** A deletion review for these emails, comma-joined IDs: opening it adopts an existing review for the same emails or prepares one. */
    data class DeleteRoute(override val id: String) : Route

    /** A deletion review the phone already holds, by its local ID. */
    data class ReviewRoute(override val id: String) : Route

    data class ExportRoute(override val id: String) : Route

    /** The export form; [id] is comma-joined IDs of emails selected for it, or empty. */
    data class ExportNewRoute(override val id: String) : Route

    data class StorageRoute(override val id: String = "-") : Route
}

private fun encode(stack: List<Route>) = stack.joinToString("|") { route ->
    val prefix = when (route) {
        is Route.ConcernRoute -> "c:"
        is Route.ChatRoute -> "t:"
        is Route.SourceRoute -> "s:"
        is Route.DeleteRoute -> "x:"
        is Route.ReviewRoute -> "r:"
        is Route.ExportRoute -> "e:"
        is Route.ExportNewRoute -> "n:"
        is Route.StorageRoute -> "g:"
    }
    prefix + route.id
}

private fun decode(text: String): List<Route> = text.split("|").filter { it.length >= 2 }.map {
    when {
        it.startsWith("c:") -> Route.ConcernRoute(it.drop(2))
        it.startsWith("t:") -> Route.ChatRoute(it.drop(2))
        it.startsWith("x:") -> Route.DeleteRoute(it.drop(2))
        it.startsWith("r:") -> Route.ReviewRoute(it.drop(2))
        it.startsWith("e:") -> Route.ExportRoute(it.drop(2))
        it.startsWith("n:") -> Route.ExportNewRoute(it.drop(2))
        it.startsWith("g:") -> Route.StorageRoute()
        else -> Route.SourceRoute(it.drop(2))
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
        SessionState.SignInRequired, is SessionState.SignedIn -> SignedIn(container, activity, destination, needsSignIn = session.session == SessionState.SignInRequired, busy = session.busy) {
            sessionModel.signIn(CredentialManagerPasskeys(activity))
        }
    }
}

@Composable
private fun SignedIn(container: AppContainer, activity: Activity, destination: MutableStateFlow<String?>, needsSignIn: Boolean, busy: Boolean, onSignIn: () -> Unit) {
    // Same instance as in AppRoot: one SessionViewModel per activity.
    val sessionModel = viewModel { SessionViewModel(container.session, container::startSync, container::signOut) }
    val inboxModel = viewModel { InboxViewModel(container.concerns) }
    val inbox by inboxModel.state.collectAsStateWithLifecycle()
    // What signing out would discard in chat; read here so the confirmation can name it.
    val chatUnsent by remember(container) { container.chat.unsent() }.collectAsStateWithLifecycle(ChatUnsent(0, 0, 0))
    val deletionPending by remember(container) { container.deletions.pending() }.collectAsStateWithLifecycle(DeletionPending(0, 0))
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.INBOX) }
    val stay = rememberSaveable { UUID.randomUUID().toString() }
    var saved by rememberSaveable { mutableStateOf("") }
    // Bumped by each notification that names the briefing, so one arriving while it is already showing still refreshes it.
    var briefingPushes by rememberSaveable { mutableIntStateOf(0) }
    val stack = decode(saved)
    fun open(route: Route) {
        saved = encode(stack + route)
    }
    fun back() {
        saved = encode(stack.dropLast(1))
    }
    val askScope = rememberCoroutineScope()
    // The pinned local model: the owner picks the downloaded file, which is installed only if its size and SHA-256 match the manifest.
    val modelState by container.models.state.collectAsStateWithLifecycle()
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            askScope.launch {
                container.models.install(uri)
                container.afterModelChange()
            }
        }
    }
    // Starts a chat about a concern, email or service, or returns to the latest one for the same subject. No request is made yet.
    fun ask(scope: ChatScope) {
        askScope.launch { container.chat.openThread(scope)?.let { open(Route.ChatRoute(it)) } }
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
        if (t == BRIEFING_DESTINATION) briefingPushes += 1
        saved = if (t == BRIEFING_DESTINATION) "" else encode(listOf(Route.ConcernRoute(t)))
        destination.value = null
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (stack.isEmpty()) {
                NavigationBar {
                    NavigationBarItem(selected = tab == Tab.INBOX, onClick = { tab = Tab.INBOX }, icon = {}, label = { Text(stringResource(R.string.nav_inbox)) })
                    NavigationBarItem(selected = tab == Tab.BRIEFING, onClick = { tab = Tab.BRIEFING }, icon = {}, label = { Text(stringResource(R.string.nav_briefing)) })
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
                    // Everything remembered below (open forms, typed text) belongs to this concern only.
                    key(id) {
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
                                        ActMessage.WRONG_ITEM -> R.string.act_wrong_item
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                        ConcernScreen(concern, now, { target, action -> concernModel.act(action, target) }, concernModel::reapply, concernModel::discard, sources, { open(Route.SourceRoute(it)) }, ::ask)
                    }
                }
                is Route.SourceRoute -> {
                    val id = route.id
                    val sourceModel = viewModel(key = "source-$id") { SourceViewModel(id, container.archive) }
                    val source by sourceModel.state.collectAsStateWithLifecycle()
                    val busy by sourceModel.busy.collectAsStateWithLifecycle()
                    val filesModel = viewModel(key = "files-$id") { AttachmentsViewModel(id, container.attachments) }
                    val files by filesModel.state.collectAsStateWithLifecycle()
                    // Which attachment a document-picker result is for survives a rotation, so a created document is never left orphaned.
                    var saving by rememberSaveable { mutableStateOf<String?>(null) }
                    val resolver = context.contentResolver
                    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
                        val wanted = saving
                        saving = null
                        if (uri != null) {
                            val info = source.text?.attachments?.firstOrNull { it.id == wanted }
                            val destination = SafDestination(resolver, uri)
                            if (info != null) filesModel.saveCopy(info, destination) else destination.discard()
                        }
                    }
                    val actions = remember(filesModel) {
                        AttachmentActions(
                            onAsk = filesModel::ask,
                            onCancel = filesModel::remove,
                            onRemove = filesModel::remove,
                            onShow = filesModel::show,
                            onSave = { info ->
                                saving = info.id
                                saveLauncher.launch(info.name)
                            },
                            onNoticeShown = filesModel::noticeShown,
                            onClosePreview = filesModel::closePreview,
                        )
                    }
                    TextButton(onClick = ::back, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.back)) }
                    SourceScreen(source, busy, files, actions, sourceModel::download, { open(Route.ConcernRoute(it)) }, ::ask, { open(Route.DeleteRoute(it)) })
                }
                is Route.DeleteRoute, is Route.ReviewRoute -> {
                    val target = if (route is Route.DeleteRoute) DeletionTarget.Selection(route.id.split(",").filter { it.isNotEmpty() }.sorted()) else DeletionTarget.Existing(route.id)
                    key(route.id) {
                        val deletionModel = viewModel(key = "delete-${route.id}") { DeletionViewModel(target, container.deletions, container.archive) }
                        val deletion by deletionModel.state.collectAsStateWithLifecycle()
                        val review = deletion.review
                        DeletionScreen(
                            deletion,
                            now,
                            confirmable = review != null && deletionModel.confirmable(review, now),
                            actions = DeletionActions(
                                onConfirm = { deletionModel.confirm(CredentialManagerPasskeys(activity)) },
                                onRetryPrepare = deletionModel::retryPrepare,
                                onPrepareAgain = deletionModel::prepareAgain,
                                onCheckStatus = deletionModel::checkStatus,
                                onRefreshCleanup = deletionModel::refreshCleanup,
                                onStopTracking = deletionModel::stopTracking,
                                onDismiss = { deletionModel.dismiss(::back) },
                                onBack = ::back,
                                onNoteShown = deletionModel::noteShown,
                            ),
                        )
                    }
                }
                is Route.ExportNewRoute -> {
                    val selected = route.id.split(",").filter { it.isNotEmpty() }
                    val builderModel = viewModel(key = "export-new-${route.id}") { ExportBuilderViewModel(selected, container.exports) }
                    val builder by builderModel.state.collectAsStateWithLifecycle()
                    // Once the request is stored, the screen moves to the export itself.
                    LaunchedEffect(builder.created) {
                        builder.created?.let { saved = encode(stack.dropLast(1) + Route.ExportRoute(it)) }
                    }
                    ExportBuilderScreen(builder, builderModel::kind, builderModel::admission, builderModel::from, builderModel::to, builderModel::sender, builderModel::recipient, builderModel::create, ::back)
                }
                is Route.ExportRoute -> {
                    val id = route.id
                    val exportModel = viewModel(key = "export-$id") { ExportViewModel(id, container.exports, container::runInBackground) }
                    val exportState by exportModel.state.collectAsStateWithLifecycle()
                    LifecycleEventEffect(Lifecycle.Event.ON_START) { exportModel.onForeground() }
                    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { exportModel.onBackground() }
                    DisposableEffect(exportModel) { onDispose { exportModel.onBackground() } }
                    val resolver = context.contentResolver
                    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                        if (uri != null) exportModel.download(SafDestination(resolver, uri))
                    }
                    ExportScreen(
                        exportState,
                        now,
                        ExportActions(
                            onSave = { name -> saveLauncher.launch(name) },
                            onRefresh = exportModel::refresh,
                            onResend = exportModel::resend,
                            onStopTracking = exportModel::stopTracking,
                            onDismiss = { exportModel.dismiss(::back) },
                            onNewExport = { saved = encode(stack.dropLast(1) + Route.ExportNewRoute("")) },
                            onBack = ::back,
                            onNoteShown = exportModel::noteShown,
                        ),
                    )
                }
                is Route.StorageRoute -> {
                    val storageModel = viewModel { StorageViewModel(container.attachments, container.exports, container.deletions) }
                    val storage by storageModel.state.collectAsStateWithLifecycle()
                    StorageScreen(
                        storage,
                        onRemoveFile = storageModel::removeFile,
                        onClearFiles = storageModel::clearFiles,
                        onOpenSource = { open(Route.SourceRoute(it)) },
                        onOpenExport = { open(Route.ExportRoute(it)) },
                        onNewExport = { open(Route.ExportNewRoute("")) },
                        onOpenDeletion = { open(Route.ReviewRoute(it)) },
                        onBack = ::back,
                    )
                }
                is Route.ChatRoute -> {
                    val id = route.id
                    val chatModel = viewModel(key = "chat-$id") { ChatThreadViewModel(id, container.chat, container.concerns, container.archive, container.syncTicks) }
                    val chatState by chatModel.state.collectAsStateWithLifecycle()
                    val draft by chatModel.draftText.collectAsStateWithLifecycle()
                    // Leaving the foreground stops the poll timers only; it never cancels a question. The app going to
                    // the background and this screen being left (back, or another screen on top) are different
                    // events, and the view model outlives both, so each stops the timers.
                    LifecycleEventEffect(Lifecycle.Event.ON_START) { chatModel.onForeground() }
                    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { chatModel.onBackground() }
                    LocalChatWork(container)
                    DisposableEffect(chatModel) { onDispose { chatModel.onBackground() } }
                    TextButton(onClick = ::back, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.back)) }
                    ChatThreadScreen(
                        chatState,
                        draft,
                        now,
                        onDraft = chatModel::onDraft,
                        onSend = chatModel::send,
                        onRefresh = chatModel::refresh,
                        onLoadEarlier = chatModel::loadEarlier,
                        onCancel = chatModel::cancel,
                        onEditAgain = chatModel::editAgain,
                        onOpenSource = { open(Route.SourceRoute(it)) },
                    )
                }
                null -> when (tab) {
                    Tab.INBOX -> InboxScreen(inbox, now, inboxModel::select, inboxModel::refresh, { open(Route.ConcernRoute(it)) })
                    Tab.BRIEFING -> {
                        // One briefing model per signed-in stay: sign-out leaves this composition and the next sign-in starts clean.
                        val briefingModel = viewModel(key = "briefing-$stay") { BriefingViewModel(container.briefing, container.concerns, container.archive::sources, container.clock) }
                        val briefing by briefingModel.state.collectAsStateWithLifecycle()
                        // Runs on every entry to this screen (tab switch, back from a concern) and on each briefing push.
                        LaunchedEffect(briefingPushes) { briefingModel.onOpened() }
                        BriefingScreen(briefing, now, briefingModel::refresh, briefingModel::markReviewed, { open(Route.ConcernRoute(it)) }, { open(Route.SourceRoute(it)) })
                    }
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
                            selection = SelectionActions(
                                onStart = archiveModel::startSelecting,
                                onStop = archiveModel::stopSelecting,
                                onToggle = archiveModel::toggle,
                                onReviewDeletion = {
                                    archiveModel.stopSelecting()
                                    open(Route.DeleteRoute(it.joinToString(",")))
                                },
                                onExportSelected = {
                                    archiveModel.stopSelecting()
                                    open(Route.ExportNewRoute(it.joinToString(",")))
                                },
                                onNewExport = { open(Route.ExportNewRoute("")) },
                            ),
                        )
                    }
                    Tab.CHAT -> {
                        val chatListModel = viewModel { ChatListViewModel(container.chat, container.concerns, container.archive) }
                        val chatList by chatListModel.state.collectAsStateWithLifecycle()
                        LifecycleEventEffect(Lifecycle.Event.ON_START) { chatListModel.onForeground() }
                        ChatListScreen(chatList, { open(Route.ChatRoute(it)) }, { scope -> chatListModel.open(scope) { open(Route.ChatRoute(it)) } })
                    }
                    Tab.SETTINGS -> SettingsScreen(
                        inbox.freshness,
                        chatUnsent,
                        now,
                        inboxModel::refresh,
                        sessionModel::signOut,
                        onNotificationsChanged = { PushRegistrationWorker.enqueue(context) },
                        onOpenStorage = { open(Route.StorageRoute()) },
                        deletionPending = deletionPending,
                        model = ModelUi(
                            modelState,
                            onChoose = { modelPicker.launch(arrayOf("*/*")) },
                            onRemove = {
                                askScope.launch {
                                    container.models.remove()
                                    container.afterModelChange()
                                }
                            },
                        ),
                    )
                }
            }
        }
    }
}

/** While a chat screen is showing, the phone offers itself for a chat turn that waits on local evidence, every few seconds. */
@Composable
private fun LocalChatWork(container: AppContainer) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                container.runLocalChatNow()
                delay(LOCAL_CHAT_POLL_MS)
            }
        }
    }
}

private const val LOCAL_CHAT_POLL_MS = 15_000L
