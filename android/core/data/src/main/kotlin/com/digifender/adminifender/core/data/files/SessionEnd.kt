package com.digifender.adminifender.core.data.files

import com.digifender.adminifender.core.data.export.ExportRepository
import com.digifender.adminifender.core.data.extraction.DeviceExtractor
import com.digifender.adminifender.core.data.extraction.ExtractionScheduler
import com.digifender.adminifender.core.data.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * What happens to private work when there is no session any more, whether the owner signed out or the server refused or
 * revoked it (`SignedOut`, `SignInRequired`): downloaded files and queued downloads go, running attachment and export
 * transfers are aborted, and device text extraction stops and removes its downloads. The owner's durable requests
 * (deletion reviews, export intents, queued edits, cached text, chat) stay: they record what the owner did, and an expired session must not lose them. Explicit sign-out clears the rest.
 */
class SessionEnd(
    private val attachments: AttachmentRepository,
    private val exports: ExportRepository,
    private val extraction: DeviceExtractor? = null,
    private val extractionScheduler: ExtractionScheduler? = null,
) {
    suspend fun ended() {
        attachments.purgeAll()
        exports.stopTransfers()
        // A running read is cancelled (it hands its file back and removes the download); anything it left is removed.
        extractionScheduler?.cancel()
        extraction?.purge()
    }

    fun watch(scope: CoroutineScope, states: Flow<SessionState>) {
        scope.launch {
            states.collect { state -> if (state is SessionState.SignedOut || state is SessionState.SignInRequired) ended() }
        }
    }
}
