package com.digifender.adminifender.core.data.network

import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A response that does not have the shape the shared contract (`contracts/schema/v1`) promises. It extends
 * [IllegalArgumentException], which the HTTP adapters already treat as "the server answered, but not with anything the
 * phone can trust": the outcome of a mutation is then unknown, and a read is unavailable.
 */
class WireException(message: String) : IllegalArgumentException(message)

private val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val TIMESTAMP = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z$")
private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
private val FIXED_CODE = Regex("^[a-z][a-z0-9_]*$")

fun isUuid(value: String) = UUID_V4.matches(value)

fun isSha256Hex(value: String) = SHA256_HEX.matches(value)

/** Strict reading helpers: every one throws [WireException] naming the path, never returns a guess. */
internal fun JsonElement.asObject(path: String): JsonObject = this as? JsonObject ?: throw WireException("$path: expected an object")

internal fun JsonElement.asArray(path: String): JsonArray = this as? JsonArray ?: throw WireException("$path: expected an array")

/** The object has exactly these keys: every [required] one, any of [optional], and nothing else. */
internal fun JsonObject.exactly(path: String, required: Set<String>, optional: Set<String> = emptySet()): JsonObject {
    for (key in required) if (key !in this) throw WireException("$path.$key: missing")
    for (key in keys) if (key !in required && key !in optional) throw WireException("$path.$key: not part of the contract")
    return this
}

internal fun JsonObject.field(key: String, path: String): JsonElement = this[key] ?: throw WireException("$path.$key: missing")

internal fun JsonObject.text(key: String, path: String, pattern: Regex? = null, max: Int = Int.MAX_VALUE): String {
    val element = field(key, path)
    val primitive = element as? JsonPrimitive
    if (primitive == null || !primitive.isString) throw WireException("$path.$key: expected a string")
    val value = primitive.content
    if (value.length > max) throw WireException("$path.$key: too long")
    if (pattern != null && !pattern.matches(value)) throw WireException("$path.$key: wrong format")
    return value
}

internal fun JsonObject.nullableText(key: String, path: String, pattern: Regex? = null): String? =
    if (field(key, path) is JsonNull) null else text(key, path, pattern)

internal fun JsonObject.uuid(key: String, path: String) = text(key, path, UUID_V4)

internal fun JsonObject.nullableUuid(key: String, path: String) = nullableText(key, path, UUID_V4)

internal fun JsonObject.digest(key: String, path: String) = text(key, path, SHA256_HEX)

internal fun JsonObject.timestamp(key: String, path: String): Long = parseTimestamp(text(key, path, TIMESTAMP), "$path.$key")

internal fun JsonObject.nullableTimestamp(key: String, path: String): Long? = if (field(key, path) is JsonNull) null else timestamp(key, path)

internal fun parseTimestamp(value: String, path: String): Long = try {
    Instant.parse(value).toEpochMilli()
} catch (_: DateTimeParseException) {
    throw WireException("$path: not a timestamp")
}

internal fun JsonObject.long(key: String, path: String, min: Long = Long.MIN_VALUE): Long {
    val primitive = field(key, path) as? JsonPrimitive
    val value = primitive?.takeIf { !it.isString }?.longOrNull ?: throw WireException("$path.$key: expected an integer")
    if (value < min) throw WireException("$path.$key: below $min")
    return value
}

internal fun JsonObject.int(key: String, path: String, min: Int = Int.MIN_VALUE): Int {
    val value = long(key, path, min.toLong())
    if (value > Int.MAX_VALUE) throw WireException("$path.$key: too large")
    return value.toInt()
}

internal fun JsonObject.bool(key: String, path: String): Boolean =
    ((field(key, path) as? JsonPrimitive)?.takeIf { !it.isString })?.booleanOrNull ?: throw WireException("$path.$key: expected true or false")

internal fun JsonObject.array(key: String, path: String): JsonArray = field(key, path).asArray("$path.$key")

internal fun JsonObject.obj(key: String, path: String): JsonObject = field(key, path).asObject("$path.$key")

internal fun <T> JsonObject.choice(key: String, path: String, allowed: Map<String, T>): T {
    val value = text(key, path)
    return allowed[value] ?: throw WireException("$path.$key: unknown value")
}

internal fun JsonObject.code(key: String, path: String): String = text(key, path, FIXED_CODE)

internal fun JsonObject.nullableCode(key: String, path: String): String? = if (field(key, path) is JsonNull) null else code(key, path)

/** A list of distinct UUIDs; [min] and [max] are the contract's bounds. */
internal fun JsonArray.uuids(path: String, min: Int = 0, max: Int = Int.MAX_VALUE): List<String> {
    if (size < min || size > max) throw WireException("$path: expected $min to $max entries")
    val ids = mapIndexed { i, element ->
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString || !UUID_V4.matches(primitive.content)) throw WireException("$path[$i]: expected an ID")
        primitive.content
    }
    if (ids.toSet().size != ids.size) throw WireException("$path: duplicate IDs")
    return ids
}

/** Entity ID to the exact revision a review was read at (revisions start at 1). */
internal fun JsonObject.revisions(path: String): Map<String, Int> = entries.associate { (id, value) ->
    if (!UUID_V4.matches(id)) throw WireException("$path: key is not an ID")
    val primitive = value as? JsonPrimitive
    val revision = primitive?.takeIf { !it.isString }?.longOrNull ?: throw WireException("$path.$id: expected a revision")
    if (revision < 1 || revision > Int.MAX_VALUE) throw WireException("$path.$id: bad revision")
    id to revision.toInt()
}
