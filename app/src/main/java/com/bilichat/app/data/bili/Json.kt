package com.bilichat.app.data.bili

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// 接口中存在超出 Long 范围的数字（如 min_seqno = 2^64-1），因此统一按原始文本读取再转换。

internal val BiliJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun parseJsonOrNull(text: String?): JsonElement? =
    if (text.isNullOrBlank()) null else runCatching { BiliJson.parseToJsonElement(text) }.getOrNull()

internal fun JsonObject.raw(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

internal fun JsonObject.str(key: String): String = raw(key).orEmpty()

internal fun JsonObject.long(key: String): Long = raw(key)?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() } ?: 0L

internal fun JsonObject.int(key: String): Int = long(key).toInt()

internal fun JsonObject.bool(key: String): Boolean = when (raw(key)) {
    "true", "1" -> true
    else -> false
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }

/** 收集对象中所有名为 `text`/`content` 的字符串，用于兜底展示未知结构的消息。 */
internal fun JsonElement.collectTexts(into: MutableList<String> = mutableListOf()): List<String> {
    when (this) {
        is JsonObject -> entries.forEach { (key, value) ->
            if ((key == "text" || key == "content") && value is JsonPrimitive && value.isString) {
                value.content.takeIf { it.isNotBlank() }?.let(into::add)
            } else {
                value.collectTexts(into)
            }
        }
        is JsonArray -> forEach { it.collectTexts(into) }
        else -> Unit
    }
    return into
}

/** B 站图片常为 http 链接，Android 默认禁止明文流量，统一转为 https。 */
internal fun normalizeUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://") -> "https://" + url.removePrefix("http://")
        else -> url
    }
}
