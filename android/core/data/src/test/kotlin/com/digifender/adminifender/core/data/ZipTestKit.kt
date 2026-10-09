package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.export.ExpectedExport
import com.digifender.adminifender.core.data.network.ExportAdmission
import com.digifender.adminifender.core.data.network.ExportCounts
import com.digifender.adminifender.core.data.network.ExportScope
import com.digifender.adminifender.core.data.network.ExportState
import com.digifender.adminifender.core.data.network.ExportStatus
import com.digifender.adminifender.core.data.network.Omission
import com.digifender.adminifender.core.data.network.OmissionReason
import com.digifender.adminifender.core.data.network.toJson
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.CRC32
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val EXPORT_ID = uuid(5000)
val EXPORT_SRC = uuid(5001)
val EXPORT_ATT = uuid(5002)
const val EXPORT_CREATED = 1_760_000_000_000L

/** Writes a ZIP the way the Worker's writer does: stored entries, UTF-8 flag, sizes and CRC in the local header, no ZIP64 or descriptors. */
class TestZip(private val sink: ((ByteArray) -> Unit)? = null) {
    private val out = ByteArrayOutputStream()

    private fun emit(bytes: ByteArray) = sink?.invoke(bytes) ?: out.write(bytes)

    private class Central(val name: ByteArray, val crc: Long, val size: Long, val offset: Long, val flags: Int, val method: Int)

    private val central = mutableListOf<Central>()
    private var offset = 0L

    private fun le(size: Int, block: ByteBuffer.() -> Unit): ByteArray = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).also(block).array()

    fun add(name: String, data: ByteArray, flags: Int = 0x0800, method: Int = 0, crcOverride: Long? = null, declaredSize: Long? = null) {
        val crc = crcOverride ?: CRC32().also { it.update(data) }.value
        val nameBytes = name.encodeToByteArray()
        val size = declaredSize ?: data.size.toLong()
        val header = le(30 + nameBytes.size) {
            putInt(0x04034b50); putShort(20); putShort(flags.toShort()); putShort(method.toShort()); putShort(0); putShort(0x21)
            putInt(crc.toInt()); putInt(size.toInt()); putInt(size.toInt()); putShort(nameBytes.size.toShort()); putShort(0); put(nameBytes)
        }
        emit(header)
        emit(data)
        central += Central(nameBytes, crc, size, offset, flags, method)
        offset += header.size + data.size
    }

    /** An entry whose bytes come from a generator, so a test can pass tens of megabytes without holding them. Returns the entry's CRC and SHA-256. */
    fun addGenerated(name: String, size: Long, chunk: Int, generate: (Long, Int) -> ByteArray): Pair<Long, String> {
        val crc = CRC32()
        val sha = MessageDigest.getInstance("SHA-256")
        var done = 0L
        while (done < size) {
            val bytes = generate(done, minOf(chunk.toLong(), size - done).toInt())
            crc.update(bytes)
            sha.update(bytes)
            done += bytes.size
        }
        val nameBytes = name.encodeToByteArray()
        val header = le(30 + nameBytes.size) {
            putInt(0x04034b50); putShort(20); putShort(0x0800); putShort(0); putShort(0); putShort(0x21)
            putInt(crc.value.toInt()); putInt(size.toInt()); putInt(size.toInt()); putShort(nameBytes.size.toShort()); putShort(0); put(nameBytes)
        }
        emit(header)
        done = 0
        while (done < size) {
            val bytes = generate(done, minOf(chunk.toLong(), size - done).toInt())
            emit(bytes)
            done += bytes.size
        }
        central += Central(nameBytes, crc.value, size, offset, 0x0800, 0)
        offset += header.size + size
        return crc.value to sha.digest().joinToString("") { "%02x".format(it) }
    }

    fun finish(centralFlagsOverride: Map<Int, Int> = emptyMap(), endEntries: Int? = null, centralNameOverride: Map<Int, String> = emptyMap(), trailing: ByteArray = ByteArray(0)): ByteArray {
        val start = offset
        var size = 0
        for ((i, c) in central.withIndex()) {
            val name = centralNameOverride[i]?.encodeToByteArray() ?: c.name
            val record = le(46 + name.size) {
                putInt(0x02014b50); putShort(20); putShort(20); putShort((centralFlagsOverride[i] ?: c.flags).toShort()); putShort(c.method.toShort()); putShort(0); putShort(0x21)
                putInt(c.crc.toInt()); putInt(c.size.toInt()); putInt(c.size.toInt()); putShort(name.size.toShort()); putShort(0); putShort(0); putShort(0); putShort(0); putInt(0); putInt(c.offset.toInt()); put(name)
            }
            emit(record)
            size += record.size
        }
        val count = endEntries ?: central.size
        emit(le(22) { putInt(0x06054b50); putShort(0); putShort(0); putShort(count.toShort()); putShort(count.toShort()); putInt(size); putInt(start.toInt()); putShort(0) })
        emit(trailing)
        return out.toByteArray()
    }
}

fun sha256(bytes: ByteArray) = sha256Of(bytes)

fun jsonl(vararg lines: String) = (lines.joinToString("\n") + if (lines.isEmpty()) "" else "\n").encodeToByteArray()

class BuiltExport(val bytes: ByteArray, val expected: ExpectedExport, val status: ExportStatus, val manifest: JsonObject)

fun defaultScope(): ExportScope = ExportScope.Archive(ExportAdmission.NORMAL)

/**
 * A complete export for [scope] as the Worker writes one. [tweak] lets a test change the manifest before it is sealed
 * into the ZIP, so a lie in the manifest can be checked against honest entries.
 */
fun buildExport(
    scope: ExportScope = defaultScope(),
    omissions: List<Omission> = listOf(Omission(uuid(5090), OmissionReason.DELETED)),
    extraEntries: List<Pair<String, ByteArray>> = emptyList(),
    attachmentName: String = "invoice.pdf",
    tweak: (MutableMap<String, JsonElement>) -> Unit = {},
    tamperEntry: ((String, ByteArray) -> ByteArray)? = null,
): BuiltExport {
    val attachment = ByteArray(3000) { (it % 97).toByte() }
    val message = "From: a@example.com\r\n\r\nhello".encodeToByteArray()
    val owner = scope is ExportScope.Archive
    val entries = mutableListOf(
        "sources.jsonl" to jsonl("""{"id":"$EXPORT_SRC","subject":"Hello"}"""),
        "attachments.jsonl" to jsonl("""{"id":"$EXPORT_ATT","sourceId":"$EXPORT_SRC","name":"$attachmentName"}"""),
        "concerns.jsonl" to jsonl(),
        "rules.jsonl" to if (owner) jsonl("""{"id":"r1"}""") else jsonl(),
        "services.jsonl" to jsonl(),
        "files/$EXPORT_SRC/message.eml" to message,
        "files/$EXPORT_SRC/$EXPORT_ATT/$attachmentName" to attachment,
    ) + extraEntries
    val counts = ExportCounts(1, 1, 0, if (owner) 1 else 0, 0)
    val manifest = mutableMapOf<String, JsonElement>(
        "schemaVersion" to JsonPrimitive(2),
        "exportId" to JsonPrimitive(EXPORT_ID),
        "snapshotSeq" to JsonPrimitive(41),
        "createdAt" to JsonPrimitive(Instant.ofEpochMilli(EXPORT_CREATED).toString()),
        "scope" to scope.toJson(),
        "counts" to buildJsonObject { put("sources", counts.sources); put("attachments", counts.attachments); put("concerns", counts.concerns); put("rules", counts.rules); put("services", counts.services) },
        "sections" to buildJsonObject { put("rules", if (owner) "included" else "omitted_by_scope"); put("services", if (owner) "included" else "omitted_by_scope"); put("partialConcerns", 0) },
        "omissions" to JsonArray(omissions.map { buildJsonObject { put("sourceId", it.sourceId); put("reason", it.reason.wire) } }),
        "files" to JsonArray(entries.map { (name, data) -> buildJsonObject { put("path", name); put("sha256", sha256(data)); put("bytes", data.size) } }),
    )
    tweak(manifest)
    val zip = TestZip()
    for ((name, data) in entries) zip.add(name, tamperEntry?.invoke(name, data) ?: data)
    zip.add("manifest.json", JsonObject(manifest).toString().encodeToByteArray())
    val bytes = zip.finish()
    val expected = ExpectedExport(EXPORT_ID, 41, EXPORT_CREATED, scope, counts, omissions)
    val status = ExportStatus(EXPORT_ID, ExportState.READY, scope, 41, EXPORT_CREATED, EXPORT_CREATED + 7 * 86_400_000L, counts, omissions, null, null)
    return BuiltExport(bytes, expected, status, JsonObject(manifest))
}
