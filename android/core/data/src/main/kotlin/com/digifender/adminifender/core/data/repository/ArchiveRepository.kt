package com.digifender.adminifender.core.data.repository

import com.digifender.adminifender.core.data.network.ArchiveApi
import com.digifender.adminifender.core.data.network.ArchiveView
import com.digifender.adminifender.core.data.network.AttachmentDto
import com.digifender.adminifender.core.data.network.ParsedQuery
import com.digifender.adminifender.core.data.network.ReadResult
import com.digifender.adminifender.core.data.network.SearchRequest
import com.digifender.adminifender.core.data.network.SourceSummary
import com.digifender.adminifender.core.data.network.isSourceId
import com.digifender.adminifender.core.data.network.parseQuery
import com.digifender.adminifender.core.model.Concern
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Attachment inventory entry; the bytes are downloaded separately and only on request. */
data class AttachmentInfo(val id: String, val name: String, val declaredType: String?, val sizeBytes: Long, val extraction: String, val extractionDetail: String?)

/**
 * A source's body text and attachment inventory. [complete] is false when the body was longer than the phone keeps,
 * so the screen can say it shows only the beginning.
 */
data class SourceText(val revision: Int, val text: String?, val complete: Boolean, val attachments: List<AttachmentInfo>)

/** A cached-only search row: the synced metadata plus a snippet from cached text when there is one. */
data class CachedSearchHit(val source: SourceSummary, val snippet: String?)

/** Read and write side of the phone's archive cache; Room in the app, in-memory in tests. */
interface ArchiveCache {
    fun sources(view: ArchiveView, limit: Int): Flow<List<SourceSummary>>

    fun count(view: ArchiveView): Flow<Int>

    fun source(id: String): Flow<SourceSummary?>

    fun sources(ids: List<String>): Flow<List<SourceSummary>>

    fun text(id: String): Flow<SourceText?>

    /** Every term must appear in the subject, a sender or the cached text; ready sources in [view] only. */
    suspend fun search(terms: List<String>, view: ArchiveView, sender: String?, fromMs: Long?, toMs: Long?, limit: Int): List<CachedSearchHit>

    /** Stores [text] only while the cached source still has `text.revision`; returns whether it was stored. */
    suspend fun putText(id: String, text: SourceText, nowMs: Long): Boolean
}

/** One search result as the screen lists it. [inAttachment] marks a match in an attachment's extracted text. */
data class SearchHit(val sourceId: String, val subject: String?, val snippet: String?, val receivedAtMs: Long, val inAttachment: Boolean, val page: Int?)

/** [REFUSED] is the server rejecting a query the phone accepted; it is not retried against the cache. */
enum class InvalidQuery { EMPTY, TOO_LONG, TOO_MANY_TERMS, REFUSED }

/** Why a search fell back to cached content; the screen must say coverage is partial (FR-013). */
enum class CachedReason { OFFLINE, SIGN_IN, UNAVAILABLE }

sealed interface SearchOutcome {
    /** Server results. While [partitionsRemaining] is above zero the order is partition-local, not a global ranking. */
    data class Online(val hits: List<SearchHit>, val nextCursor: String?, val partitionsRemaining: Int) : SearchOutcome

    /** Only content cached on this phone was searched. */
    data class CachedOnly(val hits: List<SearchHit>, val reason: CachedReason) : SearchOutcome

    data class Invalid(val reason: InvalidQuery) : SearchOutcome

    /** A further page could not be read; results already shown stay valid. */
    data class MoreUnavailable(val code: String) : SearchOutcome
}

sealed interface DownloadOutcome {
    /** Fetched and stored for offline reading; the cached flow now carries it. */
    data class Saved(val text: SourceText) : DownloadOutcome

    /** Fetched but not stored, because the phone's copy of the source is missing or at another revision. */
    data class NotSaved(val text: SourceText) : DownloadOutcome

    data object Gone : DownloadOutcome

    data object SignIn : DownloadOutcome

    data class Unavailable(val code: String) : DownloadOutcome
}

const val SEARCH_PAGE = 50
private const val CHUNK_PAGE = 200

/** The phone keeps at most this much body text per source; longer bodies are marked incomplete. */
const val MAX_CACHED_TEXT_CHARS = 1_000_000

/**
 * Archive list, exact search and source detail (lld.md#android). The list and linked concerns come from the synced
 * cache; search and text are server reads with an explicitly labelled cached-only fallback.
 */
class ArchiveRepository(private val api: ArchiveApi, private val cache: ArchiveCache, private val concerns: CacheSource, private val clock: () -> Long) {
    fun archive(view: ArchiveView, limit: Int): Flow<List<SourceSummary>> = cache.sources(view, limit)

    fun count(view: ArchiveView): Flow<Int> = cache.count(view)

    fun source(id: String): Flow<SourceSummary?> = cache.source(id)

    fun sources(ids: List<String>): Flow<List<SourceSummary>> = cache.sources(ids)

    fun cachedText(id: String): Flow<SourceText?> = cache.text(id)

    /** Cached concerns that cite [sourceId], most recently updated first. */
    fun linkedConcerns(sourceId: String): Flow<List<Concern>> =
        concerns.concerns().map { list -> list.filter { sourceId in it.sourceIds }.sortedWith(compareByDescending<Concern> { it.updatedAtMs }.thenBy { it.id }) }

    suspend fun search(raw: String, view: ArchiveView, sender: String?, fromMs: Long?, toMs: Long?, cursor: String? = null): SearchOutcome {
        val parsed = when (val p = parseQuery(raw)) {
            ParsedQuery.Empty -> return SearchOutcome.Invalid(InvalidQuery.EMPTY)
            ParsedQuery.TooLong -> return SearchOutcome.Invalid(InvalidQuery.TOO_LONG)
            ParsedQuery.TooManyTerms -> return SearchOutcome.Invalid(InvalidQuery.TOO_MANY_TERMS)
            is ParsedQuery.Ok -> p
        }
        val senderFilter = sender?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.take(320)
        val result = api.search(SearchRequest(parsed.text, view, senderFilter, fromMs, toMs), cursor, SEARCH_PAGE)
        if (result is ReadResult.Ok) {
            val page = result.value
            return SearchOutcome.Online(
                page.results.map { SearchHit(it.sourceId, it.subject, it.snippet, Instant.parse(it.receivedAt).toEpochMilli(), it.attachmentId != null, it.page) },
                page.nextCursor,
                page.partitionsRemaining,
            )
        }
        if (cursor != null) {
            return SearchOutcome.MoreUnavailable(
                when (result) {
                    ReadResult.CursorExpired -> "cursor_expired"
                    ReadResult.SessionExpired -> "session_expired"
                    is ReadResult.Unavailable -> result.code
                    is ReadResult.Ok -> error("unreachable")
                },
            )
        }
        val reason = when (result) {
            ReadResult.SessionExpired -> CachedReason.SIGN_IN
            is ReadResult.Unavailable -> if (result.code == "invalid_request") return SearchOutcome.Invalid(InvalidQuery.REFUSED) else if (result.code == "network") CachedReason.OFFLINE else CachedReason.UNAVAILABLE
            else -> CachedReason.UNAVAILABLE
        }
        val cached = cache.search(parsed.terms, view, senderFilter, fromMs, toMs, SEARCH_PAGE)
        return SearchOutcome.CachedOnly(cached.map { SearchHit(it.source.id, it.source.subject, it.snippet, it.source.receivedAtMs, false, null) }, reason)
    }

    /** Fetches body text and attachment inventory, then keeps them for offline reading when still current. */
    suspend fun download(id: String): DownloadOutcome {
        if (!isSourceId(id)) return DownloadOutcome.Gone
        val detail = when (val r = api.source(id)) {
            is ReadResult.Ok -> r.value
            else -> return failure(r)
        }
        var text = detail.text
        var complete = true
        if (text == null && detail.textInChunks) {
            val body = StringBuilder()
            var cursor: String? = null
            reading@ while (true) {
                val page = when (val r = api.chunks(id, cursor, CHUNK_PAGE)) {
                    is ReadResult.Ok -> r.value
                    else -> return failure(r)
                }
                // Body chunks come first and are contiguous; attachment text follows and is not part of the body.
                for (chunk in page.chunks) {
                    if (chunk.attachmentId != null) break@reading
                    if (body.length + chunk.text.length > MAX_CACHED_TEXT_CHARS) {
                        complete = false
                        break@reading
                    }
                    body.append(chunk.text)
                }
                cursor = page.nextCursor ?: break
            }
            text = body.toString()
        } else if (text != null && text.length > MAX_CACHED_TEXT_CHARS) {
            text = text.take(MAX_CACHED_TEXT_CHARS)
            complete = false
        }
        val source = SourceText(detail.source.revision, text, complete, detail.attachments.map(AttachmentDto::toInfo))
        return if (cache.putText(id, source, clock())) DownloadOutcome.Saved(source) else DownloadOutcome.NotSaved(source)
    }

    private fun failure(result: ReadResult<*>): DownloadOutcome = when (result) {
        ReadResult.SessionExpired -> DownloadOutcome.SignIn
        is ReadResult.Unavailable -> if (result.code == "not_found") DownloadOutcome.Gone else DownloadOutcome.Unavailable(result.code)
        else -> DownloadOutcome.Unavailable("unexpected")
    }
}

fun AttachmentDto.toInfo() = AttachmentInfo(id, name, declaredType, sizeBytes, extraction, extractionDetail)

/**
 * A short excerpt of [text] around the first matching term, marked with brackets like the server's snippets.
 * Returns null when no term occurs in the text (the match was in the subject or sender).
 */
fun cachedSnippet(text: String?, terms: List<String>, radius: Int = 60): String? {
    if (text.isNullOrEmpty()) return null
    val (at, term) = terms.mapNotNull { t -> text.indexOf(t, ignoreCase = true).takeIf { it >= 0 }?.let { it to t } }.minByOrNull { it.first } ?: return null
    val start = (at - radius).coerceAtLeast(0)
    val end = (at + term.length + radius).coerceAtMost(text.length)
    val before = text.substring(start, at)
    val match = text.substring(at, at + term.length)
    val after = text.substring(at + term.length, end)
    val excerpt = "$before[$match]$after".replace(Regex("\\s+"), " ").trim()
    return (if (start > 0) "…" else "") + excerpt + (if (end < text.length) "…" else "")
}
