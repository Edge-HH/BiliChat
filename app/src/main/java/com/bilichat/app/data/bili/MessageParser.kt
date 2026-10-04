package com.bilichat.app.data.bili

import com.bilichat.app.domain.model.ChatMessage
import com.bilichat.app.domain.model.Emote
import com.bilichat.app.domain.model.MessageContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object MessageParser {

    fun parseMessage(json: JsonObject): ChatMessage {
        val type = json.int("msg_type")
        return ChatMessage(
            seqno = json.long("msg_seqno"),
            key = json.str("msg_key"),
            senderUid = json.long("sender_uid"),
            receiverId = json.long("receiver_id"),
            receiverType = json.int("receiver_type"),
            type = type,
            timestamp = json.long("timestamp"),
            status = json.int("msg_status"),
            source = json.int("msg_source"),
            content = parseContent(type, json.str("content")),
        )
    }

    fun parseEmotes(array: JsonArray?): Map<String, Emote> = array.orEmpty()
        .filterIsInstance<JsonObject>()
        .mapNotNull { info ->
            val text = info.str("text")
            val url = normalizeUrl(info.raw("url") ?: info.raw("uri") ?: info.raw("gif_url"))
            if (text.isEmpty() || url == null) null else text to Emote(url, large = info.int("size") >= 2)
        }
        .toMap()

    fun parseContent(type: Int, raw: String): MessageContent {
        val element = parseJsonOrNull(raw)
        val json = element as? JsonObject
        return when (type) {
            1 -> MessageContent.Text(json?.str("content") ?: raw)
            2, 6 -> json?.let(::image) ?: MessageContent.Unsupported(type)
            5 -> MessageContent.Withdraw(
                (element as? JsonPrimitive)?.content ?: json?.str("content") ?: raw.trim('"'),
            )
            7 -> json?.let(::share) ?: MessageContent.Unsupported(type)
            9 -> json?.let {
                MessageContent.Card("小程序", it.str("title").ifEmpty { it.str("name") }, "", normalizeUrl(it.raw("cover")), it.raw("jump_uri"))
            } ?: MessageContent.Unsupported(type)
            10 -> json?.let(::notification) ?: MessageContent.Unsupported(type)
            11 -> json?.let(::videoPush) ?: MessageContent.Unsupported(type)
            12 -> json?.let(::articlePush) ?: MessageContent.Unsupported(type)
            13 -> json?.let {
                MessageContent.Card("图片", it.str("title"), "", normalizeUrl(it.raw("pic_url")), it.raw("jump_url"))
            } ?: MessageContent.Unsupported(type)
            14 -> json?.let {
                MessageContent.Card(
                    label = it.str("source").ifEmpty { "分享" },
                    title = it.str("title"),
                    text = it.str("desc").ifEmpty { it.str("author") },
                    coverUrl = normalizeUrl(it.raw("cover")),
                    url = it.raw("url"),
                )
            } ?: MessageContent.Unsupported(type)
            16 -> json?.let(::followPush) ?: MessageContent.Unsupported(type)
            18 -> MessageContent.Tip(
                parseJsonOrNull(json?.str("content"))?.collectTexts()?.joinToString("\n").orEmpty()
                    .ifEmpty { json?.str("content").orEmpty() },
            )
            19 -> element?.collectTexts()?.takeIf { it.isNotEmpty() }
                ?.let { MessageContent.Text(it.joinToString("\n")) }
                ?: MessageContent.Unsupported(type)
            else -> fallback(type, element)
        }
    }

    private fun image(json: JsonObject): MessageContent {
        val url = normalizeUrl(json.raw("url")) ?: return MessageContent.Unsupported(2)
        return MessageContent.Image(url, json.int("width"), json.int("height"))
    }

    private fun share(json: JsonObject): MessageContent {
        val bvid = json.str("bvid")
        val label = when (json.int("source")) {
            2 -> "相簿"
            5 -> "视频"
            6 -> "专栏"
            16 -> json.str("source_desc").ifEmpty { "分享" }
            else -> "分享"
        }
        return MessageContent.Card(
            label = label,
            title = json.str("title").ifEmpty { json.str("headline") },
            text = json.str("author"),
            coverUrl = normalizeUrl(json.raw("thumb")),
            url = if (bvid.isNotEmpty()) "https://www.bilibili.com/video/$bvid" else json.raw("url"),
        )
    }

    private fun notification(json: JsonObject): MessageContent {
        val modules = json.arr("modules")?.objects().orEmpty()
            .joinToString("\n") { "${it.str("title")}：${it.str("detail")}" }
        val config = json.obj("jump_uri_config")
        val url = config?.raw("android_uri")?.takeIf { it.startsWith("http") }
            ?: config?.raw("all_uri")?.takeIf { it.isNotEmpty() }
            ?: config?.raw("web_uri")?.takeIf { it.isNotEmpty() }
            ?: json.raw("jump_uri")?.takeIf { it.isNotEmpty() }
        val biz = json.obj("biz_content")
        return MessageContent.Card(
            label = "通知",
            title = json.str("title"),
            text = listOf(json.str("text"), modules).filter { it.isNotBlank() }.joinToString("\n"),
            coverUrl = normalizeUrl(biz?.raw("cover")?.ifEmpty { null } ?: biz?.raw("backup_cover")),
            url = url,
        )
    }

    private fun videoPush(json: JsonObject): MessageContent {
        val bvid = json.str("bvid")
        val avid = json.long("rid")
        return MessageContent.Card(
            label = "视频",
            title = json.str("title").ifEmpty { "视频已失效" },
            text = json.obj("attach_msg")?.str("content")?.ifEmpty { null } ?: json.str("desc"),
            coverUrl = normalizeUrl(json.raw("cover")),
            url = when {
                bvid.isNotEmpty() -> "https://www.bilibili.com/video/$bvid"
                avid > 0 -> "https://www.bilibili.com/video/av$avid"
                else -> null
            },
        )
    }

    private fun articlePush(json: JsonObject): MessageContent {
        val cv = json.long("rid")
        return MessageContent.Card(
            label = "专栏",
            title = json.str("title"),
            text = json.obj("attach_msg")?.str("content")?.ifEmpty { null } ?: json.str("summary"),
            coverUrl = normalizeUrl((json.arr("image_urls")?.firstOrNull() as? JsonPrimitive)?.content),
            url = if (cv > 0) "https://www.bilibili.com/read/cv$cv" else null,
        )
    }

    private fun followPush(json: JsonObject): MessageContent {
        val cards = json.arr("sub_cards")?.objects().orEmpty()
        return MessageContent.Card(
            label = "推荐",
            title = json.str("main_title").ifEmpty { "更多内容" },
            text = cards.joinToString("\n") { "· ${it.str("field1")}" }.ifEmpty { json.str("reply_content") },
            coverUrl = normalizeUrl(cards.firstOrNull()?.raw("cover_url")),
            url = cards.firstOrNull()?.raw("jump_url"),
        )
    }

    private fun fallback(type: Int, element: JsonElement?): MessageContent {
        val texts = element?.collectTexts().orEmpty()
        return if (texts.isEmpty()) MessageContent.Unsupported(type) else MessageContent.Tip(texts.joinToString("\n"))
    }
}
