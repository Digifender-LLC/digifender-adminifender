package com.digifender.adminifender.core.data.network

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Authenticated, bounded briefing transport. Private response bodies never enter logs or error messages. */
class HttpBriefingApi(private val client: OkHttpClient, private val baseUrl: HttpUrl, private val tokens: AccessTokens) : BriefingApi {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun url() = baseUrl.newBuilder().addPathSegments("api/v1/briefings")

    private fun Response.text(): String {
        if (body.contentLength() > MAX_RESPONSE_BYTES) throw IOException("response_too_large")
        val source = body.source()
        if (source.request(MAX_RESPONSE_BYTES + 1)) throw IOException("response_too_large")
        return source.readUtf8()
    }

    override suspend fun current(): ReadResult<BriefingDto> {
        val access = tokens.current() ?: return ReadResult.SessionExpired
        return try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url().addPathSegment("current").build()).header("authorization", "Bearer $access").get().build()
                client.newCall(request).execute().use { response ->
                    if (response.code == 401) {
                        tokens.rejected(access)
                        return@withContext ReadResult.SessionExpired
                    }
                    val text = response.text()
                    if (response.isSuccessful) ReadResult.Ok(wireJson.decodeFromString(Envelope.serializer(BriefingDto.serializer()), text).data)
                    else ReadResult.Unavailable(code(text))
                }
            }
        } catch (_: IOException) {
            ReadResult.Unavailable("network")
        } catch (_: IllegalArgumentException) {
            ReadResult.Unavailable("malformed_response")
        }
    }

    override suspend fun review(id: String, idempotencyKey: String): BriefingReviewResult {
        if (!isSourceId(id)) return BriefingReviewResult.Rejected("not_found")
        val access = tokens.current() ?: return BriefingReviewResult.SessionExpired
        return try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url().addPathSegment(id).addPathSegment("review").build())
                    .header("authorization", "Bearer $access").header("idempotency-key", idempotencyKey)
                    .post("{}".toRequestBody(jsonType)).build()
                client.newCall(request).execute().use { response ->
                    if (response.code == 401) {
                        tokens.rejected(access)
                        return@withContext BriefingReviewResult.SessionExpired
                    }
                    val text = response.text()
                    when {
                        response.isSuccessful -> BriefingReviewResult.Reviewed(wireJson.decodeFromString(Envelope.serializer(BriefingReviewDto.serializer()), text).data)
                        response.code == 409 && code(text) == "already_reviewed" -> BriefingReviewResult.AlreadyReviewed
                        response.code == 429 || response.code >= 500 -> BriefingReviewResult.NoAnswer
                        else -> BriefingReviewResult.Rejected(code(text))
                    }
                }
            }
        } catch (_: IOException) {
            BriefingReviewResult.NoAnswer
        } catch (_: IllegalArgumentException) {
            BriefingReviewResult.NoAnswer
        }
    }

    private fun code(text: String) = try {
        wireJson.decodeFromString(ErrorEnvelope.serializer(), text).error.code
    } catch (_: IllegalArgumentException) {
        "unreadable_error"
    }
}
