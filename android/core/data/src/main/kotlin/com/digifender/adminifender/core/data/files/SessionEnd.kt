package com.digifender.adminifender.core.data.files

import com.digifender.adminifender.core.data.export.ExportRepository
import com.digifender.adminifender.core.data.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * What happens to private work when there is no session any more, whether the owner signed out or the server refused or
 * revoked it (`SignedOut`, `SignInRequired`): downloaded files and queued downloads go, running attachment and export
 * transfers are aborted. The owner's durable requests (deletion reviews, export intents, queued edits, cached text,
 * chat) stay: they record what the owner did, and an expired session must not lose them. Explicit sign-out clears the rest.
 */
class SessionEnd(private val attachments: AttachmentRepository, private val exports: ExportRepository) {
    suspend fun ended() {
        attachments.purgeAll()
        exports.stopTransfers()
    }

    fun watch(scope: CoroutineScope, states: Flow<SessionState>) {
        scope.launch {
            states.collect { state -> if (state is SessionState.SignedOut || state is SessionState.SignInRequired) ended() }
        }
    }
}
