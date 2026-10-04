package com.digifender.adminifender.push

import com.digifender.adminifender.AdminifenderApplication
import com.digifender.adminifender.core.data.session.SessionState
import com.digifender.adminifender.core.model.PushEvent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking

/**
 * FCM receiver. A push is a hint to look: it is validated, shown with generic text and triggers a sync. It never
 * changes local state or runs an action (FR-031); the inbox is the source of truth whether or not a push arrives.
 */
class MessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val event = PushEvent.parse(message.data) ?: return
        val container = (application as AdminifenderApplication).container
        // A signed-out phone shows nothing, even if a late push arrives before the server sees the sign-out. The
        // process may have been started by this push, so the saved session is read first (a short local read).
        val session = runBlocking { container.session.restore() }
        if (session !is SessionState.SignedIn && session !is SessionState.SignInRequired) return
        show(this, notificationSpec(event))
        container.scheduler.requestNow()
    }

    override fun onNewToken(token: String) {
        // The token itself is read again by the worker; it is not passed through WorkManager's database.
        PushRegistrationWorker.enqueue(this)
    }
}
