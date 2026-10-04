package com.digifender.adminifender.core.data.repository

import com.digifender.adminifender.core.data.network.CallResult
import com.digifender.adminifender.core.data.network.OwnerApi
import com.digifender.adminifender.core.data.network.ReviewDto
import com.digifender.adminifender.core.data.session.PasskeyAuthenticator
import com.digifender.adminifender.core.data.session.RecentAuthOutcome
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The server's preview in typed form; null fields were absent, and an unknown effect cannot be confirmed. */
data class ReviewPreview(
    val subjects: List<String?> = emptyList(),
    val attachments: Int = 0,
    val concernsErased: Int = 0,
    val concernsDetached: Int = 0,
    val deviceName: String? = null,
    val currentDevice: Boolean = false,
)

/** Reads the preview the server bound to the digest; null when it cannot be shown faithfully. */
fun ReviewDto.previewOrNull(): ReviewPreview? {
    val p = preview as? JsonObject ?: return null
    return when (effect) {
        "archive.delete" -> ReviewPreview(
            subjects = (p["sources"] as? JsonArray ?: return null).map { (it as? JsonObject)?.get("subject")?.jsonPrimitive?.contentOrNull },
            attachments = p["attachments"]?.jsonPrimitive?.intOrNull ?: 0,
            concernsErased = (p["concernsErased"] as? JsonArray)?.size ?: 0,
            concernsDetached = (p["concernsDetached"] as? JsonArray)?.size ?: 0,
        )
        "device.revoke" -> ReviewPreview(
            deviceName = (p["device"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull ?: return null,
            currentDevice = p["current"]?.jsonPrimitive?.booleanOrNull == true,
        )
        else -> null
    }
}

sealed interface ReviewOutcome {
    data class Ready(val review: ReviewDto) : ReviewOutcome

    data class Refused(val code: String) : ReviewOutcome

    data object NoAnswer : ReviewOutcome

    data object SignInRequired : ReviewOutcome
}

sealed interface ConfirmOutcome {
    data class Done(val operationId: String) : ConfirmOutcome

    /** The targets changed or the review expired: the owner must review again. Nothing was changed. */
    data object ReviewAgain : ConfirmOutcome

    data object PasskeyCancelled : ConfirmOutcome

    data class Refused(val code: String) : ConfirmOutcome

    /** No answer arrived; the effect may or may not have happened. Confirming again with the same key is safe. */
    data object Unknown : ConfirmOutcome

    data object SignInRequired : ConfirmOutcome
}

/** Fresh passkey proof for the initiating session (FR-001); the app backs it with SessionManager. */
fun interface RecentAuth {
    suspend fun confirm(passkeys: PasskeyAuthenticator): RecentAuthOutcome
}

/**
 * Reviewed effects (lld.md#actions): the server builds the exact preview, and confirming needs the same digest and a
 * recent passkey on this session. Nothing is queued for later: an effect happens only while the owner confirms it.
 */
class ReviewFlow(private val api: OwnerApi, private val recentAuth: RecentAuth, private val newKey: () -> String = { UUID.randomUUID().toString() }) {
    suspend fun deleteSources(sourceIds: List<String>): ReviewOutcome =
        review("archive.delete", buildJsonObject { put("sourceIds", JsonArray(sourceIds.map(::JsonPrimitive))) })

    suspend fun revokeDevice(deviceId: String): ReviewOutcome = review("device.revoke", buildJsonObject { put("deviceId", JsonPrimitive(deviceId)) })

    private suspend fun review(effect: String, params: JsonObject): ReviewOutcome = when (val r = api.createReview(newKey(), effect, params)) {
        is CallResult.Ok -> ReviewOutcome.Ready(r.value)
        is CallResult.Refused -> ReviewOutcome.Refused(r.code)
        CallResult.NoAnswer -> ReviewOutcome.NoAnswer
        CallResult.SessionExpired -> ReviewOutcome.SignInRequired
    }

    /**
     * Confirms [review] with [key] (kept by the caller, so a retry after no answer is the same request). If the server
     * wants a recent passkey, the owner is asked once and the confirmation is sent again.
     */
    suspend fun confirm(review: ReviewDto, key: String, passkeys: PasskeyAuthenticator): ConfirmOutcome {
        var prompted = false
        while (true) {
            when (val r = api.confirmReview(review.reviewId, key, review.paramsDigest)) {
                is CallResult.Ok -> return ConfirmOutcome.Done(r.value.operationId)
                CallResult.NoAnswer -> return ConfirmOutcome.Unknown
                CallResult.SessionExpired -> return ConfirmOutcome.SignInRequired
                is CallResult.Refused -> when (r.code) {
                    "recent_auth_required" -> {
                        if (prompted) return ConfirmOutcome.Refused(r.code)
                        prompted = true
                        when (recentAuth.confirm(passkeys)) {
                            is RecentAuthOutcome.Confirmed -> Unit
                            RecentAuthOutcome.Cancelled -> return ConfirmOutcome.PasskeyCancelled
                            RecentAuthOutcome.SignInRequired -> return ConfirmOutcome.SignInRequired
                            is RecentAuthOutcome.Rejected, is RecentAuthOutcome.Unavailable -> return ConfirmOutcome.Refused("recent_auth_failed")
                        }
                    }
                    "stale_review", "review_expired", "review_mismatch" -> return ConfirmOutcome.ReviewAgain
                    // A replay of a confirmation that already went through reports the original operation.
                    "review_consumed" -> return ConfirmOutcome.Refused(r.code)
                    else -> return ConfirmOutcome.Refused(r.code)
                }
            }
        }
    }
}
