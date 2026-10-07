package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.devicework.ModelVerification
import com.digifender.adminifender.core.data.devicework.PinnedModelVerifier
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PinnedModelVerifierTest {
    @get:Rule val folder = TemporaryFolder()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun pinAndArtifactAreBothRequiredBeforeLoading() = runTest {
        val bytes = "synthetic model fixture; not an actual inference artifact".toByteArray()
        val artifact = File(folder.root, "synthetic.litertlm").apply { writeBytes(bytes) }
        val manifest = """{"modelId":"synthetic-fixture","sha256":"${hash(bytes)}","byteLength":${bytes.size},"runtimeVersion":"synthetic-v1","contextTokens":2048,"inputLimit":1536,"outputLimit":256,"tokenizerDigest":"${"a".repeat(64)}"}""".toByteArray()
        val verifier = PinnedModelVerifier(folder.root, "synthetic-v1")
        assertTrue(verifier.verify(manifest, hash(manifest), artifact) is ModelVerification.Verified)
        assertEquals(ModelVerification.Unavailable("manifest_hash_mismatch"), verifier.verify(manifest, "0".repeat(64), artifact))
        artifact.writeBytes(bytes.reversedArray())
        assertEquals(ModelVerification.Unavailable("model_hash_mismatch"), verifier.verify(manifest, hash(manifest), artifact))
        assertEquals(ModelVerification.Unavailable("manifest_unsupported"), PinnedModelVerifier(folder.root, "different-runtime").verify(manifest, hash(manifest), artifact))
    }
}
