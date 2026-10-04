package com.digifender.adminifender.ui

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

private enum class Tab { INBOX, SETTINGS }

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
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(container.clock()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_TICK_MS)
            now = container.clock()
        }
    }
    BackHandler(enabled = openId != null) { openId = null }
    // A notification tap names a concern; the detail screen reads it from the cache like any other open.
    val target by destination.collectAsStateWithLifecycle()
    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        tab = Tab.INBOX
        openId = if (t == BRIEFING_DESTINATION) null else t
        destination.value = null
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (openId == null) {
                NavigationBar {
                    NavigationBarItem(selected = tab == Tab.INBOX, onClick = { tab = Tab.INBOX }, icon = {}, label = { Text(stringResource(R.string.nav_inbox)) })
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
            val id = openId
            when {
                id != null -> {
                    val concernModel = viewModel(key = "concern-$id") { ConcernViewModel(id, container.concerns) }
                    val concern by concernModel.state.collectAsStateWithLifecycle()
                    val message by concernModel.message.collectAsStateWithLifecycle()
                    TextButton(onClick = { openId = null }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.back)) }
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
                    ConcernScreen(concern, now, concernModel::act, concernModel::reapply, concernModel::discard)
                }
                tab == Tab.INBOX -> InboxScreen(inbox, now, inboxModel::select, inboxModel::refresh, { openId = it })
                else -> SettingsScreen(inbox.freshness, now, inboxModel::refresh, sessionModel::signOut, onNotificationsChanged = { PushRegistrationWorker.enqueue(context) })
            }
        }
    }
}
