package com.bilichat.app

import android.content.Context
import androidx.core.content.edit
import com.bilichat.app.data.AccountManager
import com.bilichat.app.data.AccountStore
import com.bilichat.app.data.ImagePreparer
import com.bilichat.app.data.SettingsRepository
import com.bilichat.app.notify.MessageNotifier
import com.bilichat.app.notify.MessageWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.UUID

/** 手动依赖注入容器，生命周期与进程一致。 */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(context)
    val notifier = MessageNotifier(context)
    val accounts = AccountManager(context, AccountStore(context), deviceId(context), notifier)
    val watcher = MessageWatcher(accounts, settings, notifier)
    val images = ImagePreparer(context)

    fun shouldMonitor(): Boolean = accounts.state.value.accounts.isNotEmpty() && settings.current.autoNotify

    private fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)
        return prefs.getString("dev_id", null) ?: UUID.randomUUID().toString().uppercase().also {
            prefs.edit { putString("dev_id", it) }
        }
    }
}
