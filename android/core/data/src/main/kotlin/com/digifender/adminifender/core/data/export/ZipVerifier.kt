package com.digifender.adminifender.core.data.export

import com.digifender.adminifender.core.data.files.toHex
import com.digifender.adminifender.core.data.network.ExportCounts
import com.digifender.adminifender.core.data.network.ExportScope
import com.digifender.adminifender.core.data.network.Omission
import com.digifender.adminifender.core.data.network.OmissionReason
import com.digifender.adminifender.core.data.network.WireException
import com.digifender.adminifender.core.data.network.array
import com.digifender.adminifender.core.data.network.asObject
import com.digifender.adminifender.core.data.network.digest
import com.digifender.adminifender.core.data.network.exactly
import com.digifender.adminifender.core.data.network.int
import com.digifender.adminifender.core.data.network.long
import com.digifender.adminifender.core.data.network.obj
import com.digifender.adminifender.core.data.network.parseExportScope
import com.digifender.adminifender.core.data.network.text
import com.digifender.adminifender.core.data.network.timestamp
import com.digifender.adminifender.core.data.network.uuid
import com.digifender.adminifender.core.data.network.wireJson
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.CRC32

/** The ZIP is not what the Worker promised. [code] is a fixed, content-free reason. */
class ZipProblem(val code: String) : IOException(code)

/** Bounds the verifier enforces. The ceiling is the Worker's own: stored entries, no ZIP64 (lld.md#exports). */
data class ZipLimits(
    val maxZipBytes: Long = 0xffff_fffeL,
    val maxEntries: Int = 65_535,
    val maxNameBytes: Int = 512,
    val maxManifestBytes: Int = 16 * 1024 * 1024,
)

/** What the export status said before the download; the ZIP's manifest must agree with it. */
data class ExpectedExport(val exportId: String, val snapshotSeq: Long, val createdAtMs: Long, val scope: ExportScope, val counts: ExportCounts, val omissions: List<Omission>)

/**
 * What the checks established. [zipSha256] is the SHA-256 of the whole ZIP file as received. It is not the manifest's
 * hashes, which are per entry: the two answer different questions and are never compared with each other.
 */
data class ExportVerification(
    val entries: Int,
    val zipBytes: Long,
    val zipSha256: String,
    val listedFiles: Int,
    val attachmentFiles: Int,
    val partialConcerns: Int,
    val rulesIncluded: Boolean,
    val servicesIncluded: Boolean,
)

/**
 * Checks an export ZIP while it streams, without extracting anything and without buffering the archive: only headers
 * and the (bounded) manifest are held, and each entry is hashed and CRC-checked as its bytes pass. Only stored entries
 * without ZIP64, encryption or data descriptors are accepted, which is exactly what the Worker writes; anything else is
 * refused, not interpreted. The local headers, the central directory and the end record must agree, entry names must be
 * plain relative paths, and the manifest must list every other entry with the same size and SHA-256 and agree with the
 * export status. It never writes a file, so a hostile entry name has nothing to traverse.
 */
class StreamingZipVerifier(private val expected: ExpectedExport, private val limits: ZipLimits = ZipLimits()) {
    private enum class At { SIGNATURE, LOCAL_FIXED, LOCAL_VARIABLE, LOCAL_DATA, CENTRAL_FIXED, CENTRAL_VARIABLE, END_FIXED, DONE }

    private class Entry(val name: String, val crc: Long, val size: Long, val sha256: String, val offset: Long, val flags: Int)

    private val whole = MessageDigest.getInstance("SHA-256")
    private val entries = mutableListOf<Entry>()
    private val names = HashSet<String>()
    private var at = At.SIGNATURE
    private var position = 0L
    private var signatureStart = 0L
    private var head = ByteArray(4)
    private var have = 0

    // The entry being read.
    private var flags = 0
    private var crcWanted = 0L
    private var size = 0L
    private var nameBytes = 0
    private var extraBytes = 0
    private var name = ""
    private var remaining = 0L
    private val crc = CRC32()
    private val sha = MessageDigest.getInstance("SHA-256")
    private var manifest: ByteArrayOutputStream? = null
    private var manifestBytes: ByteArray? = null

    // The central directory.
    private var centralStart = -1L
    private var centralBytes = 0L
    private var centralIndex = 0
    private var variable = 0
    private var commentBytes = 0

    private fun want(n: Int, next: At) {
        if (head.size < n) head = ByteArray(n)
        have = 0
        wanted = n
        at = next
    }

    private var wanted = 4

    /** Feeds the next bytes of the stream. */
    fun accept(buffer: ByteArray, offset: Int, length: Int) {
        if (position + length > limits.maxZipBytes) throw ZipProblem("size_limit")
        whole.update(buffer, offset, length)
        var i = offset
        val end = offset + length
        while (i < end) {
            when (at) {
                At.DONE -> throw ZipProblem("trailing_data")
                At.LOCAL_DATA -> {
                    val n = minOf((end - i).toLong(), remaining).toInt()
                    crc.update(buffer, i, n)
                    sha.update(buffer, i, n)
                    manifest?.write(buffer, i, n)
                    remaining -= n
                    i += n
                    position += n
                    if (remaining == 0L) finishEntry()
                }
                else -> {
                    val n = minOf(end - i, wanted - have)
                    System.arraycopy(buffer, i, head, have, n)
                    have += n
                    i += n
                    position += n
                    if (have == wanted) advance()
                }
            }
        }
    }

    private fun u16(at: Int) = ByteBuffer.wrap(head, at, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun u32(at: Int) = ByteBuffer.wrap(head, at, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL

    private fun advance() {
        when (at) {
            At.SIGNATURE -> {
                signatureStart = position - 4
                when (u32(0)) {
                    LOCAL -> want(26, At.LOCAL_FIXED)
                    CENTRAL -> {
                        if (centralStart < 0) centralStart = signatureStart
                        want(42, At.CENTRAL_FIXED)
                    }
                    END -> {
                        if (centralStart < 0) centralStart = signatureStart
                        want(18, At.END_FIXED)
                    }
                    else -> throw ZipProblem("bad_signature")
                }
            }
            At.LOCAL_FIXED -> {
                if (manifestBytes != null) throw ZipProblem("manifest_not_last")
                if (entries.size >= limits.maxEntries) throw ZipProblem("entry_limit")
                flags = u16(2)
                // Bit 0 is encryption and bit 3 a trailing data descriptor; bit 11 (UTF-8 names) is the only one the Worker sets.
                if (flags and 0x1 != 0 || flags and 0x8 != 0) throw ZipProblem("unsupported_flags")
                if (u16(4) != 0) throw ZipProblem("unsupported_compression")
                crcWanted = u32(10)
                val compressed = u32(14)
                size = u32(18)
                if (compressed == 0xffff_ffffL || size == 0xffff_ffffL) throw ZipProblem("zip64_unsupported")
                if (compressed != size) throw ZipProblem("size_mismatch")
                nameBytes = u16(22)
                extraBytes = u16(24)
                if (nameBytes == 0 || nameBytes > limits.maxNameBytes || extraBytes > MAX_EXTRA) throw ZipProblem("bad_name")
                want(nameBytes + extraBytes, At.LOCAL_VARIABLE)
            }
            At.LOCAL_VARIABLE -> {
                name = decodeName(head, nameBytes)
                if (!isSafeEntryName(name)) throw ZipProblem("unsafe_name")
                if (!names.add(name)) throw ZipProblem("duplicate_name")
                if (position + size > limits.maxZipBytes) throw ZipProblem("size_limit")
                crc.reset()
                sha.reset()
                manifest = if (name == MANIFEST) ByteArrayOutputStream() else null
                if (name == MANIFEST && size > limits.maxManifestBytes) throw ZipProblem("manifest_too_large")
                remaining = size
                at = At.LOCAL_DATA
                if (remaining == 0L) finishEntry()
            }
            At.CENTRAL_FIXED -> {
                nameBytes = u16(24)
                extraBytes = u16(26)
                commentBytes = u16(28)
                val index = centralIndex
                if (index >= entries.size) throw ZipProblem("central_mismatch")
                val entry = entries[index]
                if (u16(6) != 0 || u16(4) != entry.flags || u32(12) != entry.crc || u32(16) != entry.size || u32(20) != entry.size || u32(38) != entry.offset || u16(30) != 0) throw ZipProblem("central_mismatch")
                variable = nameBytes + extraBytes + commentBytes
                // Kept so the name can be compared once the variable part has arrived.
                centralHeader = head.copyOf(42)
                if (nameBytes != entry.name.encodeToByteArray().size) throw ZipProblem("central_mismatch")
                if (variable == 0) throw ZipProblem("central_mismatch")
                want(variable, At.CENTRAL_VARIABLE)
            }
            At.CENTRAL_VARIABLE -> {
                val entry = entries[centralIndex]
                if (!head.copyOfRange(0, nameBytes).contentEquals(entry.name.encodeToByteArray())) throw ZipProblem("central_mismatch")
                centralBytes += 46L + variable
                centralIndex += 1
                want(4, At.SIGNATURE)
            }
            At.END_FIXED -> {
                if (u16(0) != 0 || u16(2) != 0 || u16(4) != entries.size || u16(6) != entries.size || u32(8) != centralBytes || u32(12) != centralStart || u16(16) != 0) throw ZipProblem("end_mismatch")
                if (centralIndex != entries.size) throw ZipProblem("central_mismatch")
                at = At.DONE
            }
            else -> Unit
        }
    }

    private var centralHeader = ByteArray(0)

    private fun finishEntry() {
        if (crc.value != crcWanted) throw ZipProblem("crc_mismatch")
        entries += Entry(name, crcWanted, size, sha.digest().toHex(), signatureStart, flags)
        if (name == MANIFEST) manifestBytes = manifest?.toByteArray()
        manifest = null
        want(4, At.SIGNATURE)
    }

    /** Ends the stream: every structure must be complete, and the manifest must hold up against the entries and the status. */
    fun finish(): ExportVerification {
        if (at != At.DONE) throw ZipProblem("truncated")
        val bytes = manifestBytes ?: throw ZipProblem("manifest_missing")
        val manifestEntry = entries.last()
        if (manifestEntry.name != MANIFEST) throw ZipProblem("manifest_not_last")
        val report = try {
            checkManifest(bytes)
        } catch (e: WireException) {
            throw ZipProblem("manifest_invalid")
        } catch (e: kotlinx.serialization.SerializationException) {
            throw ZipProblem("manifest_invalid")
        }
        return ExportVerification(
            entries = entries.size,
            zipBytes = position,
            zipSha256 = whole.digest().toHex(),
            listedFiles = report.listed,
            attachmentFiles = report.attachments,
            partialConcerns = report.partialConcerns,
            rulesIncluded = report.rules,
            servicesIncluded = report.services,
        )
    }

    private class Report(val listed: Int, val attachments: Int, val partialConcerns: Int, val rules: Boolean, val services: Boolean)

    private fun checkManifest(bytes: ByteArray): Report {
        val root = wireJson.parseToJsonElement(decodeStrict(bytes)).asObject("$").exactly("$", setOf("schemaVersion", "exportId", "snapshotSeq", "createdAt", "scope", "counts", "sections", "omissions", "files"))
        fun mismatch(what: String): Nothing = throw ZipProblem("manifest_mismatch_$what")
        if (root.int("schemaVersion", "$") != SCHEMA_VERSION) throw ZipProblem("manifest_schema")
        if (root.uuid("exportId", "$") != expected.exportId) mismatch("export")
        if (root.long("snapshotSeq", "$", 0) != expected.snapshotSeq) mismatch("snapshot")
        if (root.timestamp("createdAt", "$") != expected.createdAtMs) mismatch("created")
        if (parseExportScope(root.obj("scope", "$"), "$.scope") != expected.scope) mismatch("scope")
        val counts = root.obj("counts", "$").exactly("$.counts", setOf("sources", "attachments", "concerns", "rules", "services")).let {
            ExportCounts(it.int("sources", "$.counts", 0), it.int("attachments", "$.counts", 0), it.int("concerns", "$.counts", 0), it.int("rules", "$.counts", 0), it.int("services", "$.counts", 0))
        }
        if (counts != expected.counts) mismatch("counts")
        val omissions = root.array("omissions", "$").mapIndexed { i, item ->
            val p = "$.omissions[$i]"
            val o = item.asObject(p).exactly(p, setOf("sourceId", "reason"))
            Omission(o.uuid("sourceId", p), OmissionReason.byWire[o.text("reason", p)] ?: throw ZipProblem("manifest_invalid"))
        }
        if (omissions.size != expected.omissions.size || omissions.toSet() != expected.omissions.toSet()) mismatch("omissions")
        val sections = root.obj("sections", "$").exactly("$.sections", setOf("rules", "services", "partialConcerns"))
        val owner = expected.scope is ExportScope.Archive
        val sectionRules = sections.text("rules", "$.sections")
        val sectionServices = sections.text("services", "$.sections")
        if (sectionRules != (if (owner) "included" else "omitted_by_scope") || sectionServices != (if (owner) "included" else "omitted_by_scope")) mismatch("sections")
        val partial = sections.int("partialConcerns", "$.sections", 0)

        val byName = entries.associateBy { it.name }
        val listed = root.array("files", "$")
        val seen = HashSet<String>()
        for ((i, item) in listed.withIndex()) {
            val p = "$.files[$i]"
            val f = item.asObject(p).exactly(p, setOf("path", "sha256", "bytes"))
            val path = f.text("path", p)
            if (!seen.add(path)) throw ZipProblem("manifest_duplicate_file")
            val entry = byName[path] ?: throw ZipProblem("listed_file_missing")
            if (path == MANIFEST || entry.sha256 != f.digest("sha256", p) || entry.size != f.long("bytes", p, 0)) throw ZipProblem("file_hash_mismatch")
        }
        // The manifest cannot list itself, so every other entry must be listed: nothing rides along unchecked.
        for (entry in entries) if (entry.name != MANIFEST && entry.name !in seen) throw ZipProblem("file_not_listed")
        for (required in REQUIRED) if (required !in byName) throw ZipProblem("required_file_missing")
        if (!owner && (byName.getValue("rules.jsonl").size != 0L || byName.getValue("services.jsonl").size != 0L)) mismatch("sections")
        val attachments = entries.count { ATTACHMENT_PATH.matches(it.name) }
        if (attachments != counts.attachments) mismatch("attachments")
        return Report(seen.size, attachments, partial, owner, owner)
    }

    private fun decodeStrict(bytes: ByteArray) = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        throw ZipProblem("manifest_invalid")
    }

    private fun decodeName(buffer: ByteArray, length: Int) = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(buffer, 0, length)).toString()
    } catch (_: CharacterCodingException) {
        throw ZipProblem("bad_name")
    }

    companion object {
        const val MANIFEST = "manifest.json"
        const val SCHEMA_VERSION = 2
        private const val LOCAL = 0x04034b50L
        private const val CENTRAL = 0x02014b50L
        private const val END = 0x06054b50L
        private const val MAX_EXTRA = 64
        private val REQUIRED = listOf("sources.jsonl", "attachments.jsonl", "concerns.jsonl", "rules.jsonl", "services.jsonl")
        private val ATTACHMENT_PATH = Regex("^files/[0-9a-f-]{36}/[0-9a-f-]{36}/.+$")
    }
}

/**
 * A plain relative path: no leading or doubled slash, no backslash, drive letter, control character, empty segment or `.` or `..`
 * segment. Nothing is ever extracted, so this is a refusal to *vouch* for a name, not a sandbox for a write.
 */
fun isSafeEntryName(name: String): Boolean {
    if (name.isEmpty() || name.length > 1024 || name.startsWith("/") || name.endsWith("/") || name.contains('\\')) return false
    if (name.any { it.code < 0x20 || it.code == 0x7f }) return false
    if (name.length >= 2 && name[1] == ':' && name[0].isLetter()) return false
    return name.split('/').none { it.isEmpty() || it == "." || it == ".." }
}
