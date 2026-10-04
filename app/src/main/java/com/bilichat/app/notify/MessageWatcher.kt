package com.bilichat.app.notify

import android.util.Log
import com.bilichat.app.data.AccountManager
import com.bilichat.app.data.AccountSession
import com.bilichat.app.data.SettingsRepository
import com.bilichat.app.domain.model.Account
import kotlinx.coroutines.CancellationException

/**
 * 一次新消息检查：依次刷新每个已登录账号的会话列表，找出未通知过的新私信并发送通知。
 */
class MessageWatcher(
    private val accounts: AccountManager,
    private val settings: SettingsRepository,
    private val notifier: MessageNotifier,
) {
    suspend fun check() {
        val all = accounts.state.value.accounts
        all.forEach { account ->
            try {
                checkAccount(account, accounts.session(account.mid), showAccount = all.size > 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "check failed for ${account.mid}", e)
            }
        }
    }

    private suspend fun checkAccount(account: Account, session: AccountSession, showAccount: Boolean) {
        val chat = session.chat
        val state = session.notifyState
        val conversations = chat.refreshSessions(maxAgeMillis = 2_000)
        notifier.cancelRead(account.mid, conversations)
        val filterAuto = settings.current.filterAutoMessages
        val baseline = state.baselineSeconds

        for (conversation in conversations) {
            val last = conversation.lastMessage ?: continue
            if (conversation.unreadCount == 0) continue
            val notified = state.notifiedSeqno(conversation.key)
            if (last.seqno <= notified) continue

            // 免打扰会话、正在查看的会话、开启通知前就存在的未读消息：只记录，不通知。
            val skip = conversation.isMuted ||
                conversation.key == chat.activeConversationKey ||
                (notified == 0L && last.timestamp < baseline)
            if (skip) {
                state.setNotifiedSeqno(conversation.key, last.seqno)
                continue
            }

            val from = maxOf(notified, conversation.ackSeqno)
            val page = try {
                chat.fetchMessages(conversation.talkerId, conversation.sessionType, size = 20, beginSeqno = from)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            val fresh = page.messages.filter { message ->
                message.seqno > from &&
                    message.senderUid != account.mid &&
                    !message.isWithdrawSignal &&
                    !message.isWithdrawn &&
                    (notified != 0L || message.timestamp >= baseline) &&
                    !(filterAuto && message.isAutomated)
            }
            state.setNotifiedSeqno(conversation.key, maxOf(last.seqno, page.messages.maxOfOrNull { it.seqno } ?: 0L))
            if (fresh.isNotEmpty()) notifier.notifyMessages(account, chat, showAccount, conversation, fresh)
        }
    }

    private companion object {
        const val TAG = "MessageWatcher"
    }
}
