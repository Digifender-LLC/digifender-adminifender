package com.digifender.adminifender.ui

/** One explicit picker choice, held in memory only. Never retarget a late result to another export/session. */
internal data class ExportCopySelection(val exportId: String, val sessionEpoch: Long) {
    fun matches(currentId: String?, currentEpoch: Long, signedIn: Boolean, busy: Boolean): Boolean =
        signedIn && !busy && exportId == currentId && sessionEpoch == currentEpoch
}
