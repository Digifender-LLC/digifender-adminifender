package com.digifender.adminifender.core.data.network

import kotlinx.serialization.Serializable

/** Which admission the owner is looking at; quarantine is never mixed into the archive view (FR-013). */
enum class ArchiveView(val wire: String) { ARCHIVE("archive"), QUARANTINE("quarantine") }

/** Exact search as the backend accepts it (lld.md#search-contracts). [q] has already passed [parseQuery]. */
data class SearchRequest(val q: String, val view: ArchiveView, val sender: String? = null, val fromMs: Long? = null, val toMs: Long? = null)

/** Authenticated archive reads. Source text and search results are fetched on demand and never come from sync. */
interface ArchiveApi {
    suspend fun source(id: String): ReadResult<SourceDetailDto>

    suspend fun chunks(id: String, cursor: String?, limit: Int): ReadResult<ChunksPageDto>

    suspend fun search(request: SearchRequest, cursor: String?, limit: Int): ReadResult<SearchPageDto>
}

@Serializable
data class AttachmentDto(
    val id: String,
    val name: String,
    val declaredType: String? = null,
    val sizeBytes: Long,
    val sha256: String,
    val disposition: String,
    val extraction: String,
    val extractionDetail: String? = null,
)

@Serializable
data class SourceDetailDto(val source: SourceDto, val text: String? = null, val textInChunks: Boolean = false, val attachments: List<AttachmentDto> = emptyList())

@Serializable
data class ChunkDto(val chunkId: String, val attachmentId: String? = null, val ordinal: Int, val page: Int? = null, val startOffset: Int? = null, val text: String)

@Serializable
data class ChunksPageDto(val chunks: List<ChunkDto>, val nextCursor: String? = null)

@Serializable
data class SearchHitDto(
    val sourceId: String,
    val chunkId: String,
    val attachmentId: String? = null,
    val partitionId: Int,
    val subject: String? = null,
    val snippet: String,
    val page: Int? = null,
    val startOffset: Int? = null,
    val receivedAt: String,
)

@Serializable
data class SearchPageDto(val results: List<SearchHitDto>, val partitionsRemaining: Int, val nextCursor: String? = null, val snapshot: Long)

/** Same limits as the backend's query parser: 1 to 200 characters and at most 20 words. */
const val MAX_QUERY_CHARS = 200
const val MAX_QUERY_TERMS = 20

sealed interface ParsedQuery {
    data class Ok(val text: String, val terms: List<String>) : ParsedQuery
    data object Empty : ParsedQuery
    data object TooLong : ParsedQuery
    data object TooManyTerms : ParsedQuery
}

fun parseQuery(raw: String): ParsedQuery {
    val text = raw.trim()
    if (text.isEmpty()) return ParsedQuery.Empty
    if (text.length > MAX_QUERY_CHARS) return ParsedQuery.TooLong
    val terms = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (terms.size > MAX_QUERY_TERMS) return ParsedQuery.TooManyTerms
    return ParsedQuery.Ok(text, terms)
}

private val SOURCE_ID = Regex("^[0-9a-f-]{36}$")

/** Identifiers from search hits, concerns or links are checked before they become a request path. */
fun isSourceId(id: String) = SOURCE_ID.matches(id)
