package com.bilichat.app.domain.model

data class ChatMessage(
    val seqno: Long,
    /** 消息唯一 id；可能超出部分 JSON 库的整数精度，因此保存为文本。 */
    val key: String,
    val senderUid: Long,
    val receiverId: Long,
    val receiverType: Int,
    val type: Int,
    /** 秒级时间戳。 */
    val timestamp: Long,
    val status: Int,
    val source: Int,
    val content: MessageContent,
) {
    val isWithdrawn: Boolean get() = status == STATUS_WITHDRAWN
    val isWithdrawSignal: Boolean get() = type == TYPE_WITHDRAW
    val isAutoReply: Boolean get() = source in AUTO_REPLY_SOURCES

    /** 自动回复、官方推送、系统提示等非真人即时发送的消息。 */
    val isAutomated: Boolean
        get() = source in AUTOMATED_SOURCES || type in AUTOMATED_TYPES

    companion object {
        const val TYPE_TEXT = 1
        const val TYPE_IMAGE = 2
        const val TYPE_WITHDRAW = 5
        const val STATUS_WITHDRAWN = 1

        private val AUTO_REPLY_SOURCES = 8..11

        /**
         * 见 msg_source 定义：5 官方推送，6 推送/通知，8~11 自动回复，12 UP 主赠言，
         * 13 粉丝团系统提示，16 系统，17 互相关注，18 系统提示，19 AI。
         */
        private val AUTOMATED_SOURCES = setOf(5, 6, 8, 9, 10, 11, 12, 13, 16, 17, 18, 19)

        /** 通知、稿件推送、被关注推送、系统提示、AI 以及粉丝团系统消息。 */
        private val AUTOMATED_TYPES = setOf(10, 11, 12, 16, 18, 19, 301, 302, 303, 304, 305, 306)
    }
}

sealed interface MessageContent {
    data class Text(val text: String) : MessageContent

    data class Image(val url: String, val width: Int, val height: Int) : MessageContent

    data class Withdraw(val targetKey: String) : MessageContent

    /** 分享、通知、稿件推送等以卡片展示的消息。 */
    data class Card(
        val label: String,
        val title: String,
        val text: String,
        val coverUrl: String?,
        val url: String?,
    ) : MessageContent

    /** 灰色居中提示，如“对方主动回复或关注你前，最多发送1条消息”。 */
    data class Tip(val text: String) : MessageContent

    data class Unsupported(val type: Int) : MessageContent
}

/** 会话列表与通知中使用的一行摘要。 */
fun MessageContent.summary(): String = when (this) {
    is MessageContent.Text -> text.replace('\n', ' ')
    is MessageContent.Image -> "[图片]"
    is MessageContent.Withdraw -> "撤回了一条消息"
    is MessageContent.Card -> listOf("[$label]", title.ifBlank { text }).joinToString(" ").trim()
    is MessageContent.Tip -> text
    is MessageContent.Unsupported -> "[暂不支持的消息]"
}

data class Emote(val url: String, val large: Boolean)
