package com.digifender.adminifender.core.data.network

import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Most sources one export may name explicitly; the Worker refuses more and the phone never sends a first page of a larger selection. */
const val MAX_EXPORT_SOURCES = 1000

/** `normal` is the ordinary archive, `quarantine` only quarantined mail, `all` both. Quarantine is never implied. */
enum class ExportAdmission(val wire: String) {
    NORMAL("normal"),
    QUARANTINE("quarantine"),
    ALL("all"),
    ;

    companion object {
        val byWire = entries.associateBy { it.wire }
    }
}

/** Received time [fromMs] inclusive and [toMs] exclusive, and exact lower-case envelope addresses. At least one criterion. */
data class ExportFilter(val fromMs: Long? = null, val toMs: Long? = null, val sender: String? = null, val recipient: String? = null) {
    val isEmpty: Boolean get() = fromMs == null && toMs == null && sender == null && recipient == null
}

/** What an export selects, fixed when it is created and shown before and after (lld.md#exports). */
sealed interface ExportScope {
    val admission: ExportAdmission

    data class Archive(override val admission: ExportAdmission) : ExportScope

    data class Filtered(override val admission: ExportAdmission, val filter: ExportFilter) : ExportScope

    /** [sourceIds] is stored sorted, whichever order the owner picked them in. */
    data class Sources(override val admission: ExportAdmission, val sourceIds: List<String>) : ExportScope
}

private val ADDRESS = Regex("^[^A-Z\\s]+$")

/** Why a scope cannot be sent. The phone checks before it stores an intent, so an invalid one is never persisted. */
enum class ScopeProblem { NO_SOURCES, TOO_MANY_SOURCES, DUPLICATE_SOURCES, BAD_SOURCE_ID, EMPTY_FILTER, BAD_ADDRESS, BAD_RANGE }

fun ExportScope.problem(): ScopeProblem? = when (this) {
    is ExportScope.Archive -> null
    is ExportScope.Filtered -> when {
        filter.isEmpty -> ScopeProblem.EMPTY_FILTER
        listOfNotNull(filter.sender, filter.recipient).any { it.isEmpty() || it.length > 320 || !ADDRESS.matches(it) } -> ScopeProblem.BAD_ADDRESS
        filter.fromMs != null && filter.toMs != null && filter.fromMs >= filter.toMs -> ScopeProblem.BAD_RANGE
        else -> null
    }
    is ExportScope.Sources -> when {
        sourceIds.isEmpty() -> ScopeProblem.NO_SOURCES
        sourceIds.size > MAX_EXPORT_SOURCES -> ScopeProblem.TOO_MANY_SOURCES
        sourceIds.toSet().size != sourceIds.size -> ScopeProblem.DUPLICATE_SOURCES
        sourceIds.any { !isUuid(it) } -> ScopeProblem.BAD_SOURCE_ID
        else -> null
    }
}

/** The same scope in its normalized form: addresses trimmed and lower-cased, source IDs sorted. */
fun ExportScope.normalized(): ExportScope = when (this) {
    is ExportScope.Archive -> this
    is ExportScope.Filtered -> copy(filter = filter.copy(sender = filter.sender?.trim()?.lowercase(), recipient = filter.recipient?.trim()?.lowercase()))
    is ExportScope.Sources -> copy(sourceIds = sourceIds.sorted())
}

private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

/** The explicit stored form (`export-scope.schema.json`): always with `admission`. This is the body of the create request, and the key's identity. */
fun ExportScope.toJson(): JsonObject = buildJsonObject {
    when (this@toJson) {
        is ExportScope.Archive -> put("kind", "archive")
        is ExportScope.Filtered -> put("kind", "filter")
        is ExportScope.Sources -> put("kind", "sources")
    }
    put("admission", admission.wire)
    when (this@toJson) {
        is ExportScope.Archive -> Unit
        is ExportScope.Filtered -> put("filter", buildJsonObject {
            filter.fromMs?.let { put("from", iso(it)) }
            filter.toMs?.let { put("to", iso(it)) }
            filter.sender?.let { put("sender", it) }
            filter.recipient?.let { put("recipient", it) }
        })
        is ExportScope.Sources -> put("sourceIds", JsonArray(sourceIds.map { JsonPrimitive(it) }))
    }
}

/** `{scope}` as `POST /exports` takes it. Always explicit, so `{}` and the explicit default can never differ by accident. */
fun createExportBody(scope: ExportScope): JsonObject = buildJsonObject { put("scope", scope.normalized().toJson()) }

fun parseExportScope(element: JsonElement, path: String = "$"): ExportScope {
    val body = element.asObject(path)
    val admission = body.choice("admission", path, ExportAdmission.byWire)
    return when (body.choice("kind", path, mapOf("archive" to 0, "filter" to 1, "sources" to 2))) {
        0 -> {
            body.exactly(path, setOf("kind", "admission"))
            ExportScope.Archive(admission)
        }
        1 -> {
            body.exactly(path, setOf("kind", "admission", "filter"))
            val filterPath = "$path.filter"
            val filter = body.obj("filter", path).exactly(filterPath, emptySet(), setOf("from", "to", "sender", "recipient"))
            if (filter.isEmpty()) throw WireException("$filterPath: at least one criterion")
            val parsed = ExportFilter(
                if ("from" in filter) filter.timestamp("from", filterPath) else null,
                if ("to" in filter) filter.timestamp("to", filterPath) else null,
                if ("sender" in filter) filter.text("sender", filterPath, ADDRESS, 320) else null,
                if ("recipient" in filter) filter.text("recipient", filterPath, ADDRESS, 320) else null,
            )
            ExportScope.Filtered(admission, parsed)
        }
        else -> {
            body.exactly(path, setOf("kind", "admission", "sourceIds"))
            ExportScope.Sources(admission, body.array("sourceIds", path).uuids("$path.sourceIds", 1, MAX_EXPORT_SOURCES))
        }
    }
}

enum class ExportState(val wire: String) {
    PENDING("pending"),
    RUNNING("running"),
    READY("ready"),
    FAILED("failed"),
    EXPIRED("expired"),
    INVALIDATED("invalidated"),
    ;

    val settled: Boolean get() = this != PENDING && this != RUNNING

    companion object {
        val byWire = entries.associateBy { it.wire }
    }
}

enum class InvalidatedReason(val wire: String) {
    DELETED("deleted"),
    QUARANTINED("quarantined"),
    ADMISSION_CHANGED("admission_changed"),
    SCOPE_UNKNOWN("scope_unknown"),
    ;

    companion object {
        val byWire = entries.associateBy { it.wire }
    }
}

enum class OmissionReason(val wire: String) {
    DELETED("deleted"),
    QUARANTINED("quarantined"),
    ADMISSION_CHANGED("admission_changed"),
    EXCLUDED_BY_ADMISSION("excluded_by_admission"),
    NOT_IN_SNAPSHOT("not_in_snapshot"),
    ;

    companion object {
        val byWire = entries.associateBy { it.wire }
    }
}

data class Omission(val sourceId: String, val reason: OmissionReason)

data class ExportCounts(val sources: Int, val attachments: Int, val concerns: Int, val rules: Int, val services: Int)

/**
 * Progress of one export (`export-status.schema.json`). [counts] and [omissions] come from the manifest and exist only
 * when ready. [scope] is null only for an export created before scopes existed, which is never downloadable.
 */
data class ExportStatus(
    val id: String,
    val state: ExportState,
    val scope: ExportScope?,
    val snapshotSeq: Long,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val counts: ExportCounts?,
    val omissions: List<Omission>?,
    val invalidatedReason: InvalidatedReason?,
    val failureCode: String?,
)

fun parseExportStatus(element: JsonElement): ExportStatus {
    val path = "$"
    val body = element.asObject(path).exactly(path, setOf("id", "state", "scope", "snapshotSeq", "createdAt", "expiresAt", "counts", "omissions", "invalidatedReason", "failureCode"))
    val state = body.choice("state", path, ExportState.byWire)
    val scopeElement = body.field("scope", path)
    val scope = if (scopeElement is JsonNull) null else parseExportScope(scopeElement, "$.scope")
    val countsElement = body.field("counts", path)
    val counts = if (countsElement is JsonNull) null else countsElement.asObject("$.counts").exactly("$.counts", setOf("sources", "attachments", "concerns", "rules", "services")).let {
        ExportCounts(it.int("sources", "$.counts", 0), it.int("attachments", "$.counts", 0), it.int("concerns", "$.counts", 0), it.int("rules", "$.counts", 0), it.int("services", "$.counts", 0))
    }
    val omissionsElement = body.field("omissions", path)
    val omissions = if (omissionsElement is JsonNull) null else omissionsElement.asArray("$.omissions").mapIndexed { i, item ->
        val p = "$.omissions[$i]"
        val entry = item.asObject(p).exactly(p, setOf("sourceId", "reason"))
        Omission(entry.uuid("sourceId", p), entry.choice("reason", p, OmissionReason.byWire))
    }
    val reasonElement = body.field("invalidatedReason", path)
    val reason = if (reasonElement is JsonNull) null else body.choice("invalidatedReason", path, InvalidatedReason.byWire)
    val failureCode = body.nullableCode("failureCode", path)?.also { if (it.length > 64) throw WireException("$.failureCode: too long") }
    // The contract's conditionals: ready carries a scope, counts and omissions, nothing else does; reasons and codes belong to their states.
    if (state == ExportState.READY) {
        if (scope == null || counts == null || omissions == null) throw WireException("$: a ready export carries scope, counts and omissions")
    } else if (counts != null || omissions != null) {
        throw WireException("$: only a ready export has counts and omissions")
    }
    if ((state == ExportState.INVALIDATED) != (reason != null)) throw WireException("$.invalidatedReason: present exactly when invalidated")
    if ((state == ExportState.FAILED) != (failureCode != null)) throw WireException("$.failureCode: present exactly when failed")
    if (scope == null && (state != ExportState.INVALIDATED || reason != InvalidatedReason.SCOPE_UNKNOWN)) throw WireException("$.scope: only a legacy invalidated export has none")
    return ExportStatus(
        body.uuid("id", path),
        state,
        scope,
        body.long("snapshotSeq", path, 0),
        body.timestamp("createdAt", path),
        body.timestamp("expiresAt", path),
        counts,
        omissions,
        reason,
        failureCode,
    )
}

/** A parsed `GET /exports/{id}/content` stream. [close] releases the connection and cancels what is unread. */
class ContentStream(
    val contentLength: Long,
    /** `x-attachment-sha256`, lower-case hex, on attachment streams; null elsewhere. */
    val declaredSha256: String?,
    val body: java.io.InputStream,
    private val cancel: () -> Unit,
) : java.io.Closeable {
    /** Stops the transfer now, even while another thread is blocked reading [body]. */
    fun abort() = cancel()

    override fun close() {
        try {
            body.close()
        } finally {
            cancel()
        }
    }
}

sealed interface OpenResult {
    data class Open(val stream: ContentStream) : OpenResult

    data object SessionExpired : OpenResult

    /** The Worker answered that the content is not there: not found, deleted, expired or no longer the scope's. [code] is its fixed code. */
    data class Gone(val code: String, val status: Int) : OpenResult

    /** The Worker refused for a reason repeating will not change (a 4xx other than gone). */
    data class Refused(val code: String, val status: Int) : OpenResult

    /** No usable answer: network, 429 or 5xx, or a 409 `not_ready`-style answer that says try later. */
    data class Unavailable(val code: String) : OpenResult
}

sealed interface CreateExportResult {
    data class Created(val status: ExportStatus) : CreateExportResult

    data object SessionExpired : CreateExportResult

    /** The Worker refused this request; no export exists for the key. */
    data class Refused(val code: String) : CreateExportResult

    /** No usable answer. The same Idempotency-Key and body may be sent again and replay the same export. */
    data class NoAnswer(val code: String) : CreateExportResult
}

sealed interface ExportRead {
    data class Ok(val status: ExportStatus) : ExportRead

    data object SessionExpired : ExportRead

    data object NotKnown : ExportRead

    data class Unavailable(val code: String) : ExportRead
}

/** `POST /exports`, `GET /exports/{id}` and the authenticated ZIP stream (lld.md#exports). */
interface ExportApi {
    suspend fun create(idempotencyKey: String, scope: ExportScope): CreateExportResult

    suspend fun status(exportId: String): ExportRead

    suspend fun openContent(exportId: String): OpenResult
}

/** `GET /attachments/{id}/content`: an authenticated byte stream, never a public link. */
interface AttachmentContentApi {
    suspend fun openAttachment(attachmentId: String): OpenResult
}

/** The status in the wire shape of `export-status.schema.json`, so a stored copy is read back by the same strict parser. */
fun ExportStatus.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("state", state.wire)
    put("scope", scope?.toJson() ?: JsonNull)
    put("snapshotSeq", snapshotSeq)
    put("createdAt", iso(createdAtMs))
    put("expiresAt", iso(expiresAtMs))
    put("counts", counts?.let { c ->
        buildJsonObject {
            put("sources", c.sources)
            put("attachments", c.attachments)
            put("concerns", c.concerns)
            put("rules", c.rules)
            put("services", c.services)
        }
    } ?: JsonNull)
    put("omissions", omissions?.let { list -> JsonArray(list.map { buildJsonObject { put("sourceId", it.sourceId); put("reason", it.reason.wire) } }) } ?: JsonNull)
    put("invalidatedReason", invalidatedReason?.wire?.let { JsonPrimitive(it) } ?: JsonNull)
    put("failureCode", failureCode?.let { JsonPrimitive(it) } ?: JsonNull)
}

/**
 * `export-request.schema.json`: the body of `POST /exports`. A missing scope, or a scope without `admission`, means the
 * ordinary archive without quarantine; the phone always sends the explicit form. Returns the explicit scope the Worker would store.
 */
fun parseExportRequest(element: JsonElement): ExportScope {
    val body = element.asObject("$").exactly("$", emptySet(), setOf("scope"))
    val scopeElement = body["scope"] ?: return ExportScope.Archive(ExportAdmission.NORMAL)
    val scope = scopeElement.asObject("$.scope")
    // Only `admission` may be missing; the rest is the stored form's own validation.
    val explicit = if ("admission" in scope) scope else JsonObject(scope + ("admission" to JsonPrimitive(ExportAdmission.NORMAL.wire)))
    return parseExportScope(explicit, "$.scope")
}
