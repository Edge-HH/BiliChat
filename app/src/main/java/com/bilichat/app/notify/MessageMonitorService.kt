package com.bilichat.app.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.bilichat.app.BiliChatApplication
import com.bilichat.app.data.formatInterval
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 常驻前台服务：按用户设置的刷新间隔检查新私信。
 * 持有可超时的部分唤醒锁，保证息屏后仍能按时检查。
 */
class MessageMonitorService : Service() {
    private val container get() = (application as BiliChatApplication).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService<PowerManager>()
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BiliChat:MessageMonitor")
            ?.apply { setReferenceCounted(false) }
        showForeground(container.settings.current.refreshIntervalSeconds, accountCount())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showForeground(container.settings.current.refreshIntervalSeconds, accountCount())
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_STICKY
    }

    private suspend fun run() {
        // 账号数量变化只更新常驻通知文案；刷新间隔变化会立即按新间隔重新开始。
        scope.launch {
            container.accounts.state.map { it.accounts.size }.distinctUntilChanged().collect { count ->
                showForeground(container.settings.current.refreshIntervalSeconds, count)
            }
        }
        container.settings.settings
            .map { it.refreshIntervalSeconds }
            .distinctUntilChanged()
            .collectLatest { seconds ->
                showForeground(seconds, accountCount())
                while (true) {
                    wakeLock?.acquire(seconds * 1000L + WAKE_LOCK_MARGIN_MS)
                    try {
                        container.watcher.check()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "check failed", e)
                    }
                    delay(seconds * 1000L)
                }
            }
    }

    private fun accountCount(): Int = container.accounts.state.value.accounts.size

    private fun showForeground(seconds: Int, accountCount: Int) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            MessageNotifier.ID_MONITOR,
            container.notifier.monitorNotification(formatInterval(seconds), accountCount),
            type,
        )
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MessageMonitor"
        private const val WAKE_LOCK_MARGIN_MS = 60_000L

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, MessageMonitorService::class.java))
            } catch (e: Exception) {
                // Android 12+ 不允许从后台启动前台服务；下次打开应用时会再次尝试。
                Log.w(TAG, "unable to start monitor", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MessageMonitorService::class.java))
        }
    }
}
