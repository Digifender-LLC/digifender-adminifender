package com.digifender.adminifender.core.data.platform

import android.graphics.Bitmap
import com.digifender.adminifender.core.data.extraction.RecognitionUnavailable
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The bundled Latin recognizer (`com.google.mlkit:text-recognition`): the model ships inside the app, so recognition needs
 * no download and no network, and nothing is sent anywhere (lld.md#text-extraction-and-ocr). Other scripts are not read.
 */
class MlKitTextRecognizer : BitmapRecognizer {
    private val client: TextRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognize(bitmap: Bitmap): String? = suspendCancellableCoroutine { continuation ->
        val task = try {
            client.process(InputImage.fromBitmap(bitmap, 0))
        } catch (e: RuntimeException) {
            continuation.resumeWithException(e)
            return@suspendCancellableCoroutine
        }
        task.addOnSuccessListener { continuation.resume(it.text.takeIf { text -> text.isNotBlank() }) }
        task.addOnFailureListener { e ->
            // A recognizer that is not ready (model not loadable, no space, busy) is not the document's fault: the file goes back to the queue.
            val unavailable = e is MlKitException && e.errorCode in UNAVAILABLE_CODES
            continuation.resumeWithException(if (unavailable) RecognitionUnavailable("ml_kit_${e.message}") else e)
        }
    }

    private companion object {
        val UNAVAILABLE_CODES = setOf(MlKitException.UNAVAILABLE, MlKitException.NOT_ENOUGH_SPACE, MlKitException.NETWORK_ISSUE, MlKitException.MODEL_HASH_MISMATCH, MlKitException.INTERNAL)
    }
}
