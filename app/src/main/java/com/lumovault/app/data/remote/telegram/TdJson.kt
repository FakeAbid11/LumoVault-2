package com.lumovault.app.data.remote.telegram

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Reads TDLib's JSON without assuming anything about how a value is encoded.
 *
 * The JSON interface documents `int32`/`int53` as numbers and `int64` as strings, but a message id
 * reaches this code from several different fields, and TDLib's own output has varied between them
 * across releases. Every reader below therefore accepts either spelling and yields the fallback
 * otherwise: an unreadable id must degrade to "skip this item", never to a crash mid-scan.
 */
internal fun JsonObject.type(): String? = this["@type"]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.stringOf(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

internal fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.longOf(key: String): Long? = when (val value = this[key]) {
    null -> null
    is JsonPrimitive -> value.longOrNull ?: value.contentOrNull?.toLongOrNull()
    else -> null
}

internal fun JsonObject.intOf(key: String): Int = when (val value = this[key]) {
    null -> 0
    is JsonPrimitive -> value.intOrNull ?: value.contentOrNull?.toIntOrNull() ?: 0
    else -> 0
}

internal fun JsonObject.longValue(key: String, fallback: Long = 0): Long = longOf(key) ?: fallback

internal fun JsonObject.flag(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull == true

/** Nested object that TDLib may send as `null` for an absent optional field. */
internal fun JsonObject.objectOf(key: String): JsonObject? = (this[key] as? JsonObject)?.takeIf { !it.isEmpty() }

internal fun JsonObject.arrayOf(key: String): List<JsonObject> =
    (this[key] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }

/** `Messages.messages` — the wrapper's field is a list of message objects. */
internal fun messagesOf(response: JsonObject): List<JsonObject> = response.arrayOf("messages")

internal fun totalCountOf(response: JsonObject): Int? = response.longOf("total_count")?.toInt()

/**
 * `Chats.chat_ids` — an array of `int53`, so an array of JSON numbers rather than of objects.
 *
 * Read as strings too, because a future TDLib that widens these to the `int64`-as-string rule would
 * otherwise silently produce an empty chat list, which the discovery flow would report as "no
 * channel found".
 */
internal fun chatIdsOf(response: JsonObject): List<Long> =
    (response["chat_ids"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { element ->
        (element as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
    }
