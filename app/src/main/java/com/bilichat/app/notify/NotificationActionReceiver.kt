package com.bilichat.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.bilichat.app.BiliChatApplication
import com.bilichat.app.domain.model.Conversation
import com.bilichat.app.domain.model.conversationKey
import kotlinx.coroutines.launch

/** 处理通知中的“回复”与“标为已读”。 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as BiliChatApplication).container
        val mid = intent.getLongExtra(EXTRA_MID, 0L)
        val talkerId = intent.getLongExtra(EXTRA_TALKER_ID, 0L)
        val sessionType = intent.getIntExtra(EXTRA_SESSION_TYPE, Conversation.SESSION_USER)
        val seqno = intent.getLongExtra(EXTRA_SEQNO, 0L)
        val key = conversationKey(sessionType, talkerId)
        if (talkerId == 0L) return
        if (!container.accounts.state.value.contains(mid)) {
            container.notifier.cancel(mid, key)
            return
        }
        val chat = container.accounts.session(mid).chat
        val pending = goAsync()
        container.appScope.launch {
            try {
                when (intent.action) {
                    ACTION_REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence(KEY_REPLY)?.toString()?.trim()
                        if (text.isNullOrEmpty()) {
                            container.notifier.appendError(mid, key, "回复内容为空")
                        } else {
                            runCatching { chat.sendText(talkerId, sessionType, text) }
                                .onSuccess { container.notifier.appendReply(mid, key, text) }
                                .onFailure { container.notifier.appendError(mid, key, "发送失败：${it.message}") }
                        }
                    }
                    ACTION_MARK_READ -> {
                        container.notifier.cancel(mid, key)
                        runCatching { chat.markRead(talkerId, sessionType, seqno) }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "com.bilichat.app.action.REPLY"
        const val ACTION_MARK_READ = "com.bilichat.app.action.MARK_READ"
        const val KEY_REPLY = "reply"
        private const val EXTRA_MID = "mid"
        private const val EXTRA_TALKER_ID = "talker_id"
        private const val EXTRA_SESSION_TYPE = "session_type"
        private const val EXTRA_SEQNO = "seqno"

        fun intent(context: Context, action: String, mid: Long, conversation: Conversation, seqno: Long): Intent =
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_MID, mid)
                .putExtra(EXTRA_TALKER_ID, conversation.talkerId)
                .putExtra(EXTRA_SESSION_TYPE, conversation.sessionType)
                .putExtra(EXTRA_SEQNO, seqno)
    }
}
