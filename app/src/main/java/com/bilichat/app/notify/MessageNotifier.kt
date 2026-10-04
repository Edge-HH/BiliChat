package com.bilichat.app.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.toBitmap
import coil3.transform.CircleCropTransformation
import com.bilichat.app.MainActivity
import com.bilichat.app.R
import com.bilichat.app.data.ChatRepository
import com.bilichat.app.domain.model.Account
import com.bilichat.app.domain.model.ChatMessage
import com.bilichat.app.domain.model.Conversation
import com.bilichat.app.domain.model.summary
import java.util.concurrent.ConcurrentHashMap

/**
 * 以会话（MessagingStyle）形式展示新私信，支持直接回复与标为已读。
 * 通知、快捷方式都以“账号 mid/会话”区分，不同账号的同一联系人互不影响。
 */
class MessageNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    /** 每个会话当前通知中展示的消息，用于追加新消息与回复。 */
    private val shown = ConcurrentHashMap<String, Shown>()

    private class Shown(
        val mid: Long,
        val conversation: Conversation,
        val messages: MutableList<NotificationCompat.MessagingStyle.Message>,
        val icon: IconCompat?,
        val lastSeqno: Long,
        /** 登录了多个账号时，在通知中注明是哪个账号收到的。 */
        val accountLabel: String?,
    ) {
        val tag: String get() = tagOf(mid, conversation.key)
    }

    private val me: Person by lazy { Person.Builder().setName("我").setKey("me").build() }

    fun createChannels() {
        manager.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_MESSAGES, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName("私信消息")
                    .setDescription("收到新的私信时通知")
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_ACCOUNT, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("账号状态")
                    .setDescription("账号登录失效等提醒")
                    .build(),
            ),
        )
        // 旧版常驻服务的通知渠道，已不再使用。
        manager.deleteNotificationChannel(LEGACY_CHANNEL_MONITOR)
    }

    fun canPost(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && manager.areNotificationsEnabled()
    }

    suspend fun notifyMessages(
        account: Account,
        chat: ChatRepository,
        showAccount: Boolean,
        conversation: Conversation,
        messages: List<ChatMessage>,
    ) {
        if (messages.isEmpty() || !canPost()) return
        if (conversation.isGroup) chat.ensureUsers(messages.map { it.senderUid })
        val tag = tagOf(account.mid, conversation.key)
        val previous = shown[tag]
        val icon = previous?.icon ?: loadAvatar(conversation.avatarUrl)
        val talker = talkerPerson(tag, conversation, icon)
        val list = previous?.messages ?: mutableListOf()
        messages.forEach { message ->
            val sender = if (conversation.isGroup) groupMember(chat, message.senderUid) else talker
            list += NotificationCompat.MessagingStyle.Message(message.content.summary(), message.timestamp * 1000, sender)
        }
        while (list.size > MAX_HISTORY) list.removeAt(0)
        val label = if (showAccount) account.name.ifEmpty { "UID ${account.mid}" } else null
        val state = Shown(account.mid, conversation, list, icon, messages.maxOf { it.seqno }, label)
        shown[tag] = state
        pushShortcut(account.mid, conversation, talker, icon)
        post(state, silent = false)
    }

    /** 通过通知直接回复后，把回复追加到通知中（不再提醒）。 */
    fun appendReply(mid: Long, key: String, text: String) {
        val state = shown[tagOf(mid, key)] ?: return cancel(mid, key)
        state.messages += NotificationCompat.MessagingStyle.Message(text, System.currentTimeMillis(), me)
        post(state, silent = true)
    }

    fun appendError(mid: Long, key: String, text: String) {
        val state = shown[tagOf(mid, key)] ?: return
        val system = Person.Builder().setName(context.getString(R.string.app_name)).build()
        state.messages += NotificationCompat.MessagingStyle.Message(text, System.currentTimeMillis(), system)
        post(state, silent = true)
    }

    fun cancel(mid: Long, key: String) {
        val tag = tagOf(mid, key)
        shown.remove(tag)
        manager.cancel(tag, ID_MESSAGE)
    }

    /** 取消已在其他端读过的会话通知。 */
    fun cancelRead(mid: Long, conversations: List<Conversation>) {
        val active = activeMessageTags()
        conversations
            .filter { it.unreadCount == 0 && tagOf(mid, it.key) in active }
            .forEach { cancel(mid, it.key) }
    }

    /** 账号退出或失效时，清除它的所有通知与快捷方式。 */
    fun clearAccount(mid: Long) {
        val prefix = "$mid/"
        shown.keys.filter { it.startsWith(prefix) }.forEach(shown::remove)
        activeMessageTags().filter { it.startsWith(prefix) }.forEach { manager.cancel(it, ID_MESSAGE) }
        runCatching {
            val ids = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }.filter { it.startsWith(prefix) }
            if (ids.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, ids)
        }
    }

    @SuppressLint("MissingPermission")
    fun notifyAccountExpired(account: Account) {
        if (!canPost()) return
        val name = account.name.ifEmpty { "UID ${account.mid}" }
        val notification = NotificationCompat.Builder(context, CHANNEL_ACCOUNT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$name 的登录已失效")
            .setContentText("已从必聊中移除该账号，请重新登录")
            .setAutoCancel(true)
            .setContentIntent(activityIntent(MainActivity.intent(context), 0))
            .build()
        manager.notify("account/${account.mid}", ID_ACCOUNT, notification)
    }

    @SuppressLint("MissingPermission")
    private fun post(state: Shown, silent: Boolean) {
        if (!canPost()) return
        val conversation = state.conversation
        // 注意：Style 自带成员函数 apply(builder)，这里不能用作用域函数 apply。
        val style = NotificationCompat.MessagingStyle(me)
        if (conversation.isGroup) {
            style.setConversationTitle(conversation.name)
            style.setGroupConversation(true)
        }
        state.messages.forEach { style.addMessage(it) }
        val chatIntent = MainActivity.chatIntent(context, state.mid, conversation.talkerId, conversation.sessionType)
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setSubText(state.accountLabel)
            .setShortcutId(state.tag)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(activityIntent(chatIntent, state.tag.hashCode()))
            .setAutoCancel(true)
            .setOnlyAlertOnce(silent)
            .setSilent(silent)
            .addAction(replyAction(state))
            .addAction(markReadAction(state))
            .build()
        manager.notify(state.tag, ID_MESSAGE, notification)
    }

    private fun replyAction(state: Shown): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY).setLabel("回复").build()
        val intent = NotificationActionReceiver.intent(
            context, NotificationActionReceiver.ACTION_REPLY, state.mid, state.conversation, state.lastSeqno,
        )
        val pending = PendingIntent.getBroadcast(
            context,
            state.tag.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.Action.Builder(IconCompat.createWithResource(context, R.drawable.ic_notification), "回复", pending)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    private fun markReadAction(state: Shown): NotificationCompat.Action {
        val intent = NotificationActionReceiver.intent(
            context, NotificationActionReceiver.ACTION_MARK_READ, state.mid, state.conversation, state.lastSeqno,
        )
        val pending = PendingIntent.getBroadcast(
            context,
            state.tag.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(IconCompat.createWithResource(context, R.drawable.ic_notification), "标为已读", pending)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    private fun activeMessageTags(): Set<String> =
        runCatching { manager.activeNotifications }.getOrDefault(emptyList())
            .filter { it.id == ID_MESSAGE }
            .mapNotNullTo(HashSet()) { it.tag }

    private fun activityIntent(intent: Intent, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun talkerPerson(tag: String, conversation: Conversation, icon: IconCompat?): Person =
        Person.Builder()
            .setName(conversation.name)
            .setKey(tag)
            .setIcon(icon)
            .setImportant(true)
            .build()

    private fun groupMember(chat: ChatRepository, uid: Long): Person {
        val card = chat.cachedUser(uid)
        return Person.Builder().setName(card?.name ?: "用户 $uid").setKey("user-$uid").build()
    }

    /** 推送长期快捷方式，使通知进入系统“对话”分区，并出现在桌面图标长按菜单中。 */
    private fun pushShortcut(mid: Long, conversation: Conversation, person: Person, icon: IconCompat?) {
        val shortcut = ShortcutInfoCompat.Builder(context, tagOf(mid, conversation.key))
            .setShortLabel(conversation.name)
            .setLongLived(true)
            .setPerson(person)
            .setIcon(icon ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(MainActivity.chatIntent(context, mid, conversation.talkerId, conversation.sessionType))
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
    }

    private suspend fun loadAvatar(url: String?): IconCompat? {
        if (url == null) return null
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(AVATAR_SIZE)
            .transformations(CircleCropTransformation())
            .allowHardware(false)
            .build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
        return IconCompat.createWithBitmap(result.image.toBitmap())
    }

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_ACCOUNT = "account"
        private const val LEGACY_CHANNEL_MONITOR = "monitor"
        private const val ID_MESSAGE = 2
        private const val ID_ACCOUNT = 3
        private const val MAX_HISTORY = 12
        private const val AVATAR_SIZE = 192

        private fun tagOf(mid: Long, key: String) = "$mid/$key"
    }
}
