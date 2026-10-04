package com.bilichat.app.domain.model

/** 表情面板中的一个表情包。 */
data class EmotePackage(
    val id: Long,
    val name: String,
    val iconUrl: String?,
    /** 颜文字包：表情本身就是文字，没有图片。 */
    val isKaomoji: Boolean,
    val emotes: List<EmoteItem>,
)

data class EmoteItem(
    /** 发送时使用的文本，如 `[doge]`；颜文字为其本身。 */
    val text: String,
    val url: String?,
    val large: Boolean,
)
