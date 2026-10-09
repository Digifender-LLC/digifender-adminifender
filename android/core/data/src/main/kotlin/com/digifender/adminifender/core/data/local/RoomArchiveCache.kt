package com.digifender.adminifender.core.data.local

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.digifender.adminifender.core.data.network.ArchiveView
import com.digifender.adminifender.core.data.network.SourceSummary
import com.digifender.adminifender.core.data.network.wireJson
import com.digifender.adminifender.core.data.repository.ArchiveCache
import com.digifender.adminifender.core.data.repository.AttachmentInfo
import com.digifender.adminifender.core.data.repository.CachedSearchHit
import com.digifender.adminifender.core.data.repository.SourceText
import com.digifender.adminifender.core.data.repository.cachedSnippet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

private val stringList = ListSerializer(String.serializer())

@Serializable
private data class StoredAttachment(
    val id: String,
    val name: String,
    val declaredType: String? = null,
    val sizeBytes: Long,
    val extraction: String,
    val extractionDetail: String? = null,
    // Absent in rows written before version 5 of the app; the next download of the email fills them.
    val sha256: String = "",
    val disposition: String = "",
)

private val attachmentList = ListSerializer(StoredAttachment.serializer())

fun SourceEntity.toSummary() = SourceSummary(id, revision, state, quarantined, privacy, receivedAt, subject, wireJson.decodeFromString(stringList, fromJson))

private fun SourceTextEntity.toText() = SourceText(
    revision,
    text,
    textComplete,
    wireJson.decodeFromString(attachmentList, attachmentsJson).map { AttachmentInfo(it.id, it.name, it.declaredType, it.sizeBytes, it.extraction, it.extractionDetail, it.sha256, it.disposition) },
)

/** Escapes `%`, `_` and the escape character so owner text is matched literally by LIKE. */
internal fun likePattern(term: String) = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

/** Room-backed [ArchiveCache]. Cached search uses bound parameters only; no owner text becomes SQL. */
class RoomArchiveCache(private val db: AppDatabase) : ArchiveCache {
    override fun sources(view: ArchiveView, limit: Int) = db.sources().observe(view == ArchiveView.QUARANTINE, limit).map { rows -> rows.map { it.toSummary() } }

    override fun count(view: ArchiveView) = db.sources().count(view == ArchiveView.QUARANTINE)

    override fun source(id: String) = db.sources().observe(id).map { it?.toSummary() }

    override fun sources(ids: List<String>): Flow<List<SourceSummary>> = db.sources().observeIds(ids).map { rows -> rows.map { it.toSummary() } }

    override fun text(id: String) = db.sourceTexts().observe(id).map { it?.toText() }

    override suspend fun search(terms: List<String>, view: ArchiveView, sender: String?, fromMs: Long?, toMs: Long?, limit: Int): List<CachedSearchHit> {
        val where = mutableListOf("s.quarantined = ?", "s.state = 'ready'")
        val args = mutableListOf<Any>(if (view == ArchiveView.QUARANTINE) 1 else 0)
        for (term in terms) {
            where += "(s.subject LIKE ? ESCAPE '\\' OR s.fromJson LIKE ? ESCAPE '\\' OR t.text LIKE ? ESCAPE '\\')"
            repeat(3) { args += likePattern(term) }
        }
        if (sender != null) {
            where += "s.fromJson LIKE ? ESCAPE '\\'"
            // Whole address, like the server: the JSON array stores each address in quotes.
            args += likePattern("\"$sender\"")
        }
        if (fromMs != null) {
            where += "s.receivedAt >= ?"
            args += fromMs
        }
        if (toMs != null) {
            where += "s.receivedAt < ?"
            args += toMs
        }
        args += limit
        val sql = "SELECT s.id, s.revision, s.state, s.quarantined, s.privacy, s.receivedAt, s.subject, s.fromJson, t.text FROM sources s " +
            "LEFT JOIN source_texts t ON t.sourceId = s.id AND t.revision = s.revision WHERE ${where.joinToString(" AND ")} ORDER BY s.receivedAt DESC, s.id LIMIT ?"
        return db.sources().search(SimpleSQLiteQuery(sql, args.toTypedArray())).map { row ->
            CachedSearchHit(
                SourceSummary(row.id, row.revision, row.state, row.quarantined, row.privacy, row.receivedAt, row.subject, wireJson.decodeFromString(stringList, row.fromJson)),
                cachedSnippet(row.text, terms),
            )
        }
    }

    override suspend fun putText(id: String, text: SourceText, nowMs: Long): Boolean = db.withTransaction {
        // A response that arrives after sync deleted or changed the source must not bring old text back.
        if (db.sources().revision(id) != text.revision) return@withTransaction false
        val attachments = text.attachments.map { StoredAttachment(it.id, it.name, it.declaredType, it.sizeBytes, it.extraction, it.extractionDetail, it.sha256, it.disposition) }
        db.sourceTexts().put(SourceTextEntity(id, text.revision, text.text, text.complete, wireJson.encodeToString(attachmentList, attachments), nowMs))
        true
    }
}
