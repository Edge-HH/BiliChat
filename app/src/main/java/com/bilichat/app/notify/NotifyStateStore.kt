package com.bilichat.app.notify

import android.content.Context
import androidx.core.content.edit

/**
 * 记录某个账号下每个会话已通知到的消息序列号，避免重复通知；
 * [baselineSeconds] 之前的消息（开启通知前就存在的未读）不会再通知。
 */
class NotifyStateStore(private val context: Context, mid: Long) {
    private val name = "notify_state_$mid"
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    val baselineSeconds: Long
        get() = prefs.getLong(KEY_BASELINE, 0L).takeIf { it > 0 } ?: reset()

    fun notifiedSeqno(key: String): Long = prefs.getLong(PREFIX + key, 0L)

    fun setNotifiedSeqno(key: String, seqno: Long) {
        if (seqno > notifiedSeqno(key)) prefs.edit { putLong(PREFIX + key, seqno) }
    }

    /** 清空记录并把基线设为现在，返回新的基线。 */
    fun reset(): Long {
        val now = System.currentTimeMillis() / 1000
        prefs.edit {
            clear()
            putLong(KEY_BASELINE, now)
        }
        return now
    }

    fun delete() {
        prefs.edit { clear() }
        context.deleteSharedPreferences(name)
    }

    private companion object {
        const val KEY_BASELINE = "baseline"
        const val PREFIX = "seq_"
    }
}
