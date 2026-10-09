package com.digifender.adminifender.core.data.extraction

import com.digifender.adminifender.core.data.network.ReadMoreApi
import com.digifender.adminifender.core.data.network.ReadMoreOutcome

/** The owner's "read more pages" for a document that was read only up to its first pages. */
class ReadMoreRepository(private val api: ReadMoreApi, private val scheduler: ExtractionScheduler, private val syncNow: () -> Unit) {
    /**
     * Asks the Worker to queue the next pages. Nothing the owner can search changes until the phone has read them; on success a
     * sync is asked for (so the file shows as waiting) and the reader is scheduled. Asking again is harmless.
     */
    suspend fun request(attachmentId: String): ReadMoreOutcome = api.readMore(attachmentId).also {
        if (it is ReadMoreOutcome.Requested) {
            syncNow()
            scheduler.requestNow()
        }
    }
}
