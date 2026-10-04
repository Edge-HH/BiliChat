package com.bilichat.app.ui.settings

import androidx.lifecycle.ViewModel
import com.bilichat.app.data.AccountManager
import com.bilichat.app.data.AppSettings
import com.bilichat.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val accounts: AccountManager,
    private val appScope: CoroutineScope,
) : ViewModel() {
    val state: StateFlow<AppSettings> = settings.settings

    private val _loggingOut = MutableStateFlow<Set<Long>>(emptySet())

    /** 正在退出的账号。 */
    val loggingOut: StateFlow<Set<Long>> = _loggingOut.asStateFlow()

    fun setAutoNotify(enabled: Boolean) {
        // 重新开启时只通知之后收到的消息，避免把关闭期间的未读一次性弹出。
        if (enabled && !settings.current.autoNotify) accounts.resetNotifyBaselines()
        settings.setAutoNotify(enabled)
    }

    fun setFilterAutoMessages(enabled: Boolean) = settings.setFilterAutoMessages(enabled)

    fun setRefreshInterval(seconds: Int) = settings.setRefreshInterval(seconds)

    fun setDynamicColor(enabled: Boolean) = settings.setDynamicColor(enabled)

    fun switchTo(mid: Long) = accounts.switchTo(mid)

    fun logout(mid: Long) {
        if (mid in _loggingOut.value) return
        _loggingOut.update { it + mid }
        // 用应用级作用域：退出过程中离开设置页或切换账号都不应中断清理。
        appScope.launch {
            try {
                accounts.logout(mid)
            } finally {
                _loggingOut.update { it - mid }
            }
        }
    }
}
