package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.export.ExpectedExport
import com.digifender.adminifender.core.data.export.StreamingZipVerifier
import com.digifender.adminifender.core.data.export.ZipLimits
import com.digifender.adminifender.core.data.export.ZipProblem
import com.digifender.adminifender.core.data.export.isSafeEntryName
import com.digifender.adminifender.core.data.network.ExportAdmission
import com.digifender.adminifender.core.data.network.ExportCounts
import com.digifender.adminifender.core.data.network.ExportScope
import com.digifender.adminifender.core.data.network.toJson
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun verify(built: BuiltExport, chunk: Int = 4096, limits: ZipLimits = ZipLimits(), bytes: ByteArray = built.bytes, expected: ExpectedExport = built.expected) =
    StreamingZipVerifier(expected, limits).let { v ->
        var at = 0
        while (at < bytes.size) {
            val n = minOf(chunk, bytes.size - at)
            v.accept(bytes, at, n)
            at += n
        }
        v.finish()
    }

private fun problem(block: () -> Unit): String {
    try {
        block()
    } catch (e: ZipProblem) {
        return e.code
    }
    fail("expected the ZIP to be refused")
    error("unreachable")
}

class ZipVerifierTest {
    @Test
    fun aWorkerShapedExportVerifiesWhateverTheChunking() {
        val built = buildExport()
        val reference = verify(built)
        assertEquals(8, reference.entries)
        assertEquals(7, reference.listedFiles)
        assertEquals(1, reference.attachmentFiles)
        assertEquals(built.bytes.size.toLong(), reference.zipBytes)
        assertTrue(reference.rulesIncluded)
        // The whole-file hash is the hash of the ZIP as received; it is not any manifest entry hash.
        assertEquals(sha256Of(built.bytes), reference.zipSha256)
        for (chunk in listOf(1, 7, 31, 64 * 1024)) assertEquals("chunk $chunk", reference, verify(built, chunk))
    }

    @Test
    fun aScopedExportOmitsOwnerSettingsAndSaysSo() {
        val built = buildExport(scope = ExportScope.Sources(ExportAdmission.QUARANTINE, listOf(EXPORT_SRC)))
        val report = verify(built)
        assertFalse(report.rulesIncluded)
        assertFalse(report.servicesIncluded)
    }

    @Test
    fun aChangedByteInAnyEntryIsCaught() {
        val built = buildExport()
        // The attachment's bytes (a late flip) fail the entry's CRC before anything else is looked at.
        val entryName = "files/$EXPORT_SRC/$EXPORT_ATT/invoice.pdf".encodeToByteArray()
        val at = built.bytes.indexOfSlice(entryName, 0) + entryName.size + 10
        val damaged = built.bytes.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
        assertEquals("crc_mismatch", problem { verify(built, bytes = damaged) })
    }

    @Test
    fun anEntryWhoseBytesAndCrcAgreeButNotWithTheManifestIsCaught() {
        val built = buildExport(tamperEntry = { name, data -> if (name.endsWith("invoice.pdf")) data.copyOf().also { it[0] = 99 } else data })
        assertEquals("file_hash_mismatch", problem { verify(built) })
    }

    @Test
    fun truncationAtAnyPointIsNeverAccepted() {
        val built = buildExport()
        for (cut in listOf(0, 3, 29, 100, 2_000, built.bytes.size / 2, built.bytes.size - 30, built.bytes.size - 22, built.bytes.size - 1)) {
            val code = try {
                val v = StreamingZipVerifier(built.expected)
                v.accept(built.bytes, 0, cut)
                v.finish()
                "accepted"
            } catch (e: ZipProblem) {
                e.code
            }
            assertTrue("cut at $cut gave $code", code != "accepted")
        }
    }

    @Test
    fun bytesAfterTheEndRecordAreRefused() {
        val built = buildExport()
        assertEquals("trailing_data", problem { verify(built, bytes = built.bytes + byteArrayOf(0)) })
    }

    @Test
    fun anEntryNotListedByTheManifestAndAListedEntryThatIsMissingAreBothCaught() {
        val extra = buildExport(extraEntries = listOf("stowaway.txt" to "x".encodeToByteArray()))
        // The helper lists every entry it writes, so build the lie by dropping the listing instead.
        val unlisted = buildExport(extraEntries = listOf("stowaway.txt" to "x".encodeToByteArray()), tweak = { m ->
            val files = (m.getValue("files") as JsonArray).filterNot { (it as JsonObject).getValue("path").toString().contains("stowaway") }
            m["files"] = JsonArray(files)
        })
        assertEquals("file_not_listed", problem { verify(unlisted) })
        val phantom = buildExport(tweak = { m ->
            m["files"] = JsonArray((m.getValue("files") as JsonArray) + buildJsonObject { put("path", "ghost.txt"); put("sha256", "a".repeat(64)); put("bytes", 1) })
        })
        assertEquals("listed_file_missing", problem { verify(phantom) })
        assertEquals(9, verify(extra).entries)
    }

    @Test
    fun entryNamesThatCouldEscapeADirectoryAreNeverVouchedFor() {
        for (bad in listOf("../evil.txt", "files/../../evil", "/abs/evil", "a\\b.txt", "C:/x", "dir//file", "files/./x", "ends/", "tab\tname", "")) {
            assertFalse("'$bad'", isSafeEntryName(bad))
        }
        for (good in listOf("sources.jsonl", "files/$EXPORT_SRC/$EXPORT_ATT/résumé (1).pdf", "files/a b/c.d")) assertTrue(good, isSafeEntryName(good))
        val built = buildExport(extraEntries = listOf("files/../../escape.txt" to "x".encodeToByteArray()))
        assertEquals("unsafe_name", problem { verify(built) })
        // A zero-length name is refused in the header before it can be read.
        val dotdot = buildExport(attachmentName = "..")
        assertEquals("unsafe_name", problem { verify(dotdot) })
    }

    @Test
    fun duplicateEntryNamesAreRefused() {
        val built = buildExport(extraEntries = listOf("sources.jsonl" to ByteArray(0)))
        assertEquals("duplicate_name", problem { verify(built) })
    }

    @Test
    fun compressedEncryptedDescriptorAndZip64EntriesAreRefusedNotInterpreted() {
        fun zipWith(flags: Int = 0x0800, method: Int = 0, declared: Long? = null): ByteArray {
            val z = TestZip()
            z.add("manifest.json", "{}".encodeToByteArray(), flags = flags, method = method, declaredSize = declared)
            return z.finish()
        }
        val built = buildExport()
        assertEquals("unsupported_compression", problem { verify(built, bytes = zipWith(method = 8)) })
        assertEquals("unsupported_flags", problem { verify(built, bytes = zipWith(flags = 0x0801)) })
        assertEquals("unsupported_flags", problem { verify(built, bytes = zipWith(flags = 0x0808)) })
        assertEquals("zip64_unsupported", problem { verify(built, bytes = zipWith(declared = 0xffff_ffffL)) })
    }

    @Test
    fun aCentralDirectoryOrEndRecordThatDisagreesWithTheEntriesIsRefused() {
        val tmp = buildExport()
        fun rebuild(central: Map<Int, Int> = emptyMap(), end: Int? = null, names: Map<Int, String> = emptyMap()): ByteArray {
            val z = TestZip()
            z.add("sources.jsonl", ByteArray(0))
            z.add("attachments.jsonl", ByteArray(0))
            return z.finish(central, end, names)
        }
        assertEquals("central_mismatch", problem { verify(tmp, bytes = rebuild(central = mapOf(0 to 0))) })
        assertEquals("central_mismatch", problem { verify(tmp, bytes = rebuild(names = mapOf(1 to "attachments.jsonX"))) })
        assertEquals("end_mismatch", problem { verify(tmp, bytes = rebuild(end = 3)) })
    }

    @Test
    fun theManifestMustAgreeWithTheStatusTheOwnerWasShown() {
        fun lie(key: String, value: JsonElement) = buildExport(tweak = { it[key] = value })
        assertEquals("manifest_mismatch_export", problem { verify(lie("exportId", JsonPrimitive(uuid(6000)))) })
        assertEquals("manifest_mismatch_snapshot", problem { verify(lie("snapshotSeq", JsonPrimitive(40))) })
        assertEquals("manifest_mismatch_created", problem { verify(lie("createdAt", JsonPrimitive("2026-01-01T00:00:00Z"))) })
        assertEquals("manifest_schema", problem { verify(lie("schemaVersion", JsonPrimitive(1))) })
        assertEquals("manifest_mismatch_scope", problem { verify(lie("scope", buildJsonObject { put("kind", "archive"); put("admission", "all") })) })
        assertEquals(
            "manifest_mismatch_counts",
            problem { verify(lie("counts", buildJsonObject { put("sources", 2); put("attachments", 1); put("concerns", 0); put("rules", 1); put("services", 0) })) },
        )
        assertEquals("manifest_mismatch_omissions", problem { verify(lie("omissions", JsonArray(emptyList()))) })
        assertEquals("manifest_invalid", problem { verify(lie("surprise", JsonPrimitive(true)).let { buildExport(tweak = { m -> m["surprise"] = JsonPrimitive(true) }) }) })
        assertEquals("manifest_mismatch_sections", problem { verify(lie("sections", buildJsonObject { put("rules", "omitted_by_scope"); put("services", "included"); put("partialConcerns", 0) })) })
    }

    @Test
    fun theManifestHashesAreEntryHashesNotTheZipHash() {
        val built = buildExport(tweak = { m ->
            // A manifest that put the whole-ZIP hash where an entry hash belongs would not verify.
            m["files"] = JsonArray((m.getValue("files") as JsonArray).map { e -> JsonObject((e as JsonObject) + ("sha256" to JsonPrimitive("f".repeat(64)))) })
        })
        assertEquals("file_hash_mismatch", problem { verify(built) })
    }

    @Test
    fun limitsAreEnforcedWhileStreaming() {
        val built = buildExport()
        assertEquals("size_limit", problem { verify(built, limits = ZipLimits(maxZipBytes = 1_000)) })
        assertEquals("entry_limit", problem { verify(built, limits = ZipLimits(maxEntries = 3)) })
        assertEquals("manifest_too_large", problem { verify(built, limits = ZipLimits(maxManifestBytes = 10)) })
        assertEquals("bad_name", problem { verify(built, limits = ZipLimits(maxNameBytes = 5)) })
    }

    @Test
    fun aLargeAttachmentStreamsThroughWithoutBeingHeld() {
        // 48 MiB generated on the fly; the verifier only ever sees 64 KiB at a time and keeps hashes, not bytes.
        val size = 48L * 1024 * 1024
        val chunk = 64 * 1024
        val generate: (Long, Int) -> ByteArray = { at, n -> ByteArray(n) { ((at + it) % 251).toByte() } }
        val name = "files/$EXPORT_SRC/$EXPORT_ATT/big.bin"
        val sha = MessageDigest.getInstance("SHA-256")
        var at = 0L
        while (at < size) {
            val b = generate(at, minOf(chunk.toLong(), size - at).toInt())
            sha.update(b)
            at += b.size
        }
        val bigSha = sha.digest().joinToString("") { "%02x".format(it) }
        val small = listOf("sources.jsonl", "attachments.jsonl", "concerns.jsonl", "rules.jsonl", "services.jsonl")
        val counts = ExportCounts(0, 1, 0, 0, 0)
        val scope = ExportScope.Sources(ExportAdmission.NORMAL, listOf(EXPORT_SRC))
        val manifest = buildJsonObject {
            put("schemaVersion", 2)
            put("exportId", EXPORT_ID)
            put("snapshotSeq", 41)
            put("createdAt", java.time.Instant.ofEpochMilli(EXPORT_CREATED).toString())
            put("scope", scope.toJson())
            put("counts", buildJsonObject { put("sources", 0); put("attachments", 1); put("concerns", 0); put("rules", 0); put("services", 0) })
            put("sections", buildJsonObject { put("rules", "omitted_by_scope"); put("services", "omitted_by_scope"); put("partialConcerns", 0) })
            put("omissions", JsonArray(emptyList()))
            put("files", JsonArray(small.map { buildJsonObject { put("path", it); put("sha256", sha256(ByteArray(0))); put("bytes", 0) } } + buildJsonObject { put("path", name); put("sha256", bigSha); put("bytes", size) }))
        }
        val verifier = StreamingZipVerifier(ExpectedExport(EXPORT_ID, 41, EXPORT_CREATED, scope, counts, emptyList()))
        var largest = 0
        var fed = 0L
        val zip = TestZip { piece ->
            largest = maxOf(largest, piece.size)
            verifier.accept(piece, 0, piece.size)
            fed += piece.size
        }
        for (n in small) zip.add(n, ByteArray(0))
        zip.addGenerated(name, size, chunk, generate)
        zip.add("manifest.json", manifest.toString().encodeToByteArray())
        zip.finish()
        val report = verifier.finish()
        assertEquals(fed, report.zipBytes)
        assertTrue(report.zipBytes > size)
        assertEquals(1, report.attachmentFiles)
        assertTrue("no piece was larger than a chunk or a header", largest <= chunk + 1024)
    }
}

private fun ByteArray.indexOfSlice(needle: ByteArray, from: Int): Int {
    outer@ for (start in from..size - needle.size) {
        for (i in needle.indices) if (this[start + i] != needle[i]) continue@outer
        return start
    }
    return -1
}
