package com.bilichat.app.ui.components

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dayFormat = DateTimeFormatter.ofPattern("M月d日")
private val fullFormat = DateTimeFormatter.ofPattern("yyyy/M/d")

/** 会话列表中的简短时间：今天显示时刻，昨天显示“昨天”，更早显示日期。 */
fun formatListTime(epochSeconds: Long): String {
    if (epochSeconds <= 0) return ""
    val dateTime = Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val date = dateTime.toLocalDate()
    return when {
        date == today -> dateTime.format(timeFormat)
        date == today.minusDays(1) -> "昨天"
        date.year == today.year -> dateTime.format(dayFormat)
        else -> dateTime.format(fullFormat)
    }
}

/** 聊天中的时间分隔条。 */
fun formatChatTime(epochSeconds: Long): String {
    val dateTime = Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val date = dateTime.toLocalDate()
    val time = dateTime.format(timeFormat)
    return when {
        date == today -> time
        date == today.minusDays(1) -> "昨天 $time"
        date.year == today.year -> "${dateTime.format(dayFormat)} $time"
        else -> "${dateTime.format(fullFormat)} $time"
    }
}
