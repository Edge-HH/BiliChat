package com.bilichat.app.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import com.bilichat.app.data.bili.BiliApi
import com.bilichat.app.data.bili.BiliApiException
import com.bilichat.app.domain.model.Account
import com.bilichat.app.notify.MessageNotifier
import com.bilichat.app.notify.NotifyStateStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/** 一个已登录账号的接口客户端、会话数据与通知记录。 */
class AccountSession(
    val mid: Long,
    val api: BiliApi,
    val chat: ChatRepository,
    val notifyState: NotifyStateStore,
)

/** 多账号的登录、切换、刷新信息与退出。 */
class AccountManager(
    private val context: Context,
    private val store: AccountStore,
    private val deviceId: String,
    private val notifier: MessageNotifier,
) {
    val state: StateFlow<AccountsState> = store.state

    /** 未登录请求（获取二维码、设备指纹等）使用的客户端。 */
    val guestApi = BiliApi(StaticCredentials(emptyMap()), deviceId) {}

    private val sessions = ConcurrentHashMap<Long, AccountSession>()

    private val _notice = MutableStateFlow<String?>(null)

    /** 需要告知用户的账号事件，如某个账号登录失效。 */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun session(mid: Long): AccountSession = sessions.getOrPut(mid) {
        val api = BiliApi(store.credentials(mid), deviceId) { onSessionExpired(mid) }
        AccountSession(mid, api, ChatRepository(api), NotifyStateStore(context, mid))
    }

    /**
     * 使用网页登录或扫码登录得到的 Cookie 添加账号并切换过去；
     * 已存在的账号会更新登录凭据。
     */
    suspend fun addAccount(cookies: Map<String, String>): Account {
        if (!Cookies.isComplete(cookies)) throw BiliApiException(-101, "未获取到登录凭据，请重试")
        val merged = cookies.toMutableMap()
        if (merged["buvid3"] == null) {
            runCatching { guestApi.fetchBuvid() }.getOrNull()?.let(merged::putAll)
        }
        val account = BiliApi(StaticCredentials(merged), deviceId) {}.nav().account
            ?: throw BiliApiException(-101, "登录失败，请重试")
        val isNew = !store.state.value.contains(account.mid)
        store.upsert(account, merged, activate = true)
        if (isNew) session(account.mid).notifyState.reset()
        return account
    }

    fun switchTo(mid: Long) = store.setActive(mid)

    /** 刷新所有账号的昵称与头像；已失效的账号会被移除。网络错误时保留缓存。 */
    suspend fun refreshAll() {
        store.state.value.accounts.forEach { account ->
            val nav = runCatching { session(account.mid).api.nav() }.getOrNull() ?: return@forEach
            if (nav.account == null) onSessionExpired(account.mid) else store.updateProfile(nav.account)
        }
    }

    suspend fun logout(mid: Long) {
        if (!store.state.value.contains(mid)) return
        runCatching { session(mid).api.logout() }
        removeLocal(mid)
    }

    /** 重新开启自动通知时调用：只通知之后收到的消息。 */
    fun resetNotifyBaselines() {
        store.state.value.accounts.forEach { session(it.mid).notifyState.reset() }
    }

    fun consumeNotice() {
        _notice.value = null
    }

    /** 接口返回“账号未登录”时调用。 */
    private fun onSessionExpired(mid: Long) {
        val account = store.account(mid) ?: return
        removeLocal(mid)
        _notice.value = "${account.name.ifEmpty { "UID ${account.mid}" }} 的登录已失效，请重新登录"
        notifier.notifyAccountExpired(account)
    }

    private fun removeLocal(mid: Long) {
        store.remove(mid)
        sessions.remove(mid)?.let { session ->
            session.chat.clear()
            session.notifyState.delete()
        }
        notifier.clearAccount(mid)
        Handler(Looper.getMainLooper()).post {
            runCatching {
                CookieManager.getInstance().apply {
                    removeAllCookies(null)
                    flush()
                }
            }
        }
    }
}
