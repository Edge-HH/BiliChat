package com.bilichat.app.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AppSettings(
    /** 后台常驻并按刷新间隔检查新私信、发送通知。 */
    val autoNotify: Boolean = false,
    /** 不为自动回复、官方推送等自动消息发送通知。 */
    val filterAutoMessages: Boolean = true,
    val refreshIntervalSeconds: Int = 10,
    /** 从壁纸提取主题色（Android 12+）。 */
    val dynamicColor: Boolean = true,
) {
    val refreshIntervalMillis: Long get() = refreshIntervalSeconds * 1000L

    companion object {
        val refreshIntervalOptions = listOf(5, 10, 15, 30, 60, 120, 300, 600)
    }
}

fun formatInterval(seconds: Int): String = when {
    seconds < 60 -> "$seconds 秒"
    seconds % 60 == 0 -> "${seconds / 60} 分钟"
    else -> "${seconds / 60} 分 ${seconds % 60} 秒"
}

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    fun setAutoNotify(enabled: Boolean) = update { it.copy(autoNotify = enabled) }

    fun setFilterAutoMessages(enabled: Boolean) = update { it.copy(filterAutoMessages = enabled) }

    fun setRefreshInterval(seconds: Int) = update { it.copy(refreshIntervalSeconds = seconds) }

    fun setDynamicColor(enabled: Boolean) = update { it.copy(dynamicColor = enabled) }

    private fun update(transform: (AppSettings) -> AppSettings) {
        _settings.update(transform)
        val s = _settings.value
        prefs.edit {
            putBoolean(KEY_AUTO_NOTIFY, s.autoNotify)
            putBoolean(KEY_FILTER_AUTO, s.filterAutoMessages)
            putInt(KEY_INTERVAL, s.refreshIntervalSeconds)
            putBoolean(KEY_DYNAMIC_COLOR, s.dynamicColor)
        }
    }

    private fun read(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            autoNotify = prefs.getBoolean(KEY_AUTO_NOTIFY, defaults.autoNotify),
            filterAutoMessages = prefs.getBoolean(KEY_FILTER_AUTO, defaults.filterAutoMessages),
            refreshIntervalSeconds = prefs.getInt(KEY_INTERVAL, defaults.refreshIntervalSeconds),
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, defaults.dynamicColor),
        )
    }

    private companion object {
        const val KEY_AUTO_NOTIFY = "auto_notify"
        const val KEY_FILTER_AUTO = "filter_auto_messages"
        const val KEY_INTERVAL = "refresh_interval_seconds"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
    }
}
