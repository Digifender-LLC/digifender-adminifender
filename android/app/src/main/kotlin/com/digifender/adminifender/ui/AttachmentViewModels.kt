package com.digifender.adminifender.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digifender.adminifender.core.data.extraction.ReadMoreRepository
import com.digifender.adminifender.core.data.files.AskOutcome
import com.digifender.adminifender.core.data.files.AttachmentPreview
import com.digifender.adminifender.core.data.files.AttachmentRepository
import com.digifender.adminifender.core.data.files.Destination
import com.digifender.adminifender.core.data.files.FileRead
import com.digifender.adminifender.core.data.files.LocalFile
import com.digifender.adminifender.core.data.files.PreviewResult
import com.digifender.adminifender.core.data.files.SaveOutcome
import com.digifender.adminifender.core.data.network.ReadMoreOutcome
import com.digifender.adminifender.core.data.repository.AttachmentInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val STOP_TIMEOUT_MS = 5_000L

/** What the last action on an attachment came to, in words the screen can choose. */
enum class AttachmentNotice { TOO_LARGE, NEEDS_REFRESH, SOURCE_GONE, SAVED, SAVE_FAILED, SAVE_LEFTOVER, DAMAGED, NOT_AVAILABLE, NOT_SHOWN, READ_MORE_ASKED, READ_MORE_NOTHING, READ_MORE_UNAVAILABLE, READ_MORE_SIGN_IN }

/** Text shown inside the app. It is plain text only; nothing in it is a link or markup. */
data class PreviewState(val attachmentId: String, val name: String, val text: String, val truncated: Boolean)

data class AttachmentsUiState(
    val files: Map<String, LocalFile>,
    val notice: AttachmentNotice?,
    val noticeName: String?,
    val preview: PreviewState?,
    val busyId: String?,
)

/** Download, remove, read and save-a-copy for one email's attachments. Every download is the owner's explicit request. */
class AttachmentsViewModel(private val sourceId: String, private val attachments: AttachmentRepository, private val readMore: ReadMoreRepository) : ViewModel() {
    private val notice = MutableStateFlow<Pair<AttachmentNotice, String?>?>(null)
    private val preview = MutableStateFlow<PreviewState?>(null)
    private val busy = MutableStateFlow<String?>(null)

    val state: StateFlow<AttachmentsUiState> = combine(attachments.files(sourceId), notice, preview, busy) { files, n, p, b ->
        AttachmentsUiState(files, n?.first, n?.second, p, b)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AttachmentsUiState(emptyMap(), null, null, null, null))

    fun ask(info: AttachmentInfo) {
        viewModelScope.launch {
            notice.value = when (attachments.ask(sourceId, info)) {
                is AskOutcome.TooLarge -> AttachmentNotice.TOO_LARGE to info.name
                AskOutcome.NeedsRefresh -> AttachmentNotice.NEEDS_REFRESH to info.name
                AskOutcome.SourceUnavailable -> AttachmentNotice.SOURCE_GONE to info.name
                AskOutcome.Asked, AskOutcome.AlreadyReady, AskOutcome.AlreadyActive -> null
            }
        }
    }

    /** Asks for the pages after the ones already read. Nothing searchable changes until the phone has read them. */
    fun readMore(info: AttachmentInfo) {
        viewModelScope.launch {
            notice.value = when (readMore.request(info.id)) {
                is ReadMoreOutcome.Requested -> AttachmentNotice.READ_MORE_ASKED to info.name
                ReadMoreOutcome.NothingToRead -> AttachmentNotice.READ_MORE_NOTHING to info.name
                is ReadMoreOutcome.NotAvailable -> AttachmentNotice.SOURCE_GONE to info.name
                ReadMoreOutcome.SessionExpired -> AttachmentNotice.READ_MORE_SIGN_IN to info.name
                is ReadMoreOutcome.Unavailable -> AttachmentNotice.READ_MORE_UNAVAILABLE to info.name
            }
        }
    }

    /** Stops a download in progress, or removes the copy on this phone. The email and its original on the server are untouched. */
    fun remove(attachmentId: String) {
        viewModelScope.launch {
            attachments.remove(attachmentId)
            if (preview.value?.attachmentId == attachmentId) preview.value = null
        }
    }

    /** Shows a bounded plain-text view of a downloaded file that is text; anything else is not opened inside the app. */
    fun show(info: AttachmentInfo) {
        if (busy.value != null) return
        busy.value = info.id
        viewModelScope.launch {
            try {
                if (!AttachmentPreview.eligible(info.declaredType, info.name, info.sizeBytes)) {
                    notice.value = AttachmentNotice.NOT_SHOWN to info.name
                    return@launch
                }
                when (val read = attachments.read(info.id)) {
                    is FileRead.Bytes -> when (val rendered = AttachmentPreview.render(read.bytes)) {
                        is PreviewResult.Text -> preview.value = PreviewState(info.id, info.name, rendered.text, rendered.truncated)
                        PreviewResult.NotShown -> notice.value = AttachmentNotice.NOT_SHOWN to info.name
                    }
                    FileRead.NotAvailable -> notice.value = AttachmentNotice.NOT_AVAILABLE to info.name
                    FileRead.Damaged -> notice.value = AttachmentNotice.DAMAGED to info.name
                }
            } finally {
                busy.value = null
            }
        }
    }

    fun closePreview() {
        preview.value = null
    }

    /** Writes a verified copy where the owner chose. That copy is outside the app's private storage and the app cannot take it back. */
    fun saveCopy(info: AttachmentInfo, destination: Destination) {
        if (busy.value != null) {
            viewModelScope.launch { destination.discard() }
            return
        }
        busy.value = info.id
        viewModelScope.launch {
            notice.value = try {
                when (val out = attachments.saveCopy(info.id, destination)) {
                    is SaveOutcome.Saved -> AttachmentNotice.SAVED to destination.label
                    SaveOutcome.NotAvailable -> AttachmentNotice.NOT_AVAILABLE to info.name
                    SaveOutcome.Damaged -> AttachmentNotice.DAMAGED to info.name
                    is SaveOutcome.WriteFailed -> (if (out.discarded) AttachmentNotice.SAVE_FAILED else AttachmentNotice.SAVE_LEFTOVER) to destination.label
                }
            } finally {
                busy.value = null
            }
        }
    }

    fun noticeShown() {
        notice.update { null }
    }
}
