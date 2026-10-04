package com.bilichat.app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.AlarmManagerCompat
import androidx.core.content.edit
import androidx.core.content.getSystemService
import com.bilichat.app.data.AccountManager
import com.bilichat.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 安排后台检查新私信。不保留常驻服务或唤醒锁，两次检查之间进程可以被系统回收。
 * - 准时模式：精确闹钟逐次唤醒，按刷新间隔检查；息屏且一直没有新消息时逐步放慢。
 *   关闭电池优化后在 Doze 中也不受限制，否则系统最多每小时放行约 72 次。
 * - 省电模式：交给 JobScheduler 约每 15 分钟执行一次，由系统与其他任务合并安排。
 */
class MessageScheduler(
    private val context: Context,
    private val settings: SettingsRepository,
    private val accounts: AccountManager,
    private val watcher: MessageWatcher,
) {
    private val alarms = context.getSystemService<AlarmManager>()
    private val jobs = context.getSystemService<JobScheduler>()
    private val power = context.getSystemService<PowerManager>()
    private val prefs = context.getSharedPreferences("scheduler", Context.MODE_PRIVATE)
    private val checking = Mutex()

    private val enabled: Boolean
        get() = accounts.state.value.accounts.isNotEmpty() && settings.current.autoNotify

    /** 跟随账号与设置变化：进程启动时只补上缺失的安排，之后每次变化都按新设置重新开始。 */
    fun bind(scope: CoroutineScope) {
        scope.launch {
            combine(accounts.state, settings.settings) { state, s ->
                Plan(state.accounts.isNotEmpty() && s.autoNotify, s.powerSaving, s.refreshIntervalSeconds, s.slowWhenIdle)
            }
                .distinctUntilChanged()
                .collectIndexed { index, _ -> if (index == 0) ensure() else restart() }
        }
    }

    /** 已安排好的检查保持不变；缺失（开机、被强行停止后）或已明显过期时补上。 */
    fun ensure() {
        when {
            !enabled -> cancelAll()
            settings.current.powerSaving -> {
                cancelAlarm()
                if (jobs?.getPendingJob(JOB_ID) == null) scheduleJob()
            }
            else -> {
                cancelJob()
                val overdue = SystemClock.elapsedRealtime() > prefs.getLong(KEY_NEXT_AT, 0L) + OVERDUE_GRACE_MILLIS
                if (overdue || alarmIntent(PendingIntent.FLAG_NO_CREATE) == null) setAlarm(nextDelayMillis())
            }
        }
    }

    /** 按当前设置重新开始，并清除息屏放慢的状态。 */
    fun restart() {
        resetIdle()
        when {
            !enabled -> cancelAll()
            settings.current.powerSaving -> {
                cancelAlarm()
                scheduleJob()
            }
            else -> {
                cancelJob()
                setAlarm(settings.current.refreshIntervalMillis)
            }
        }
    }

    /** 回到应用时恢复按刷新间隔检查；省电模式不重排，以免推迟系统已安排好的检查。 */
    fun onAppForeground() {
        if (enabled && !settings.current.powerSaving) restart() else ensure()
    }

    /** 准时模式的闹钟触发。 */
    internal suspend fun onAlarm() {
        if (!enabled || settings.current.powerSaving) {
            cancelAlarm()
            return
        }
        // 先排好下一次：即使这次检查中途进程被回收，闹钟链也不会断。
        setAlarm(nextDelayMillis())
        val found = check() ?: return
        recordIdle(found)
        setAlarm(nextDelayMillis())
    }

    /** 省电模式的定期任务触发。 */
    internal suspend fun onJob() {
        if (!enabled || !settings.current.powerSaving) {
            cancelJob()
            return
        }
        check()
    }

    /** 执行一次检查，返回是否发出了新消息通知；已有检查在进行或检查超时返回 null。 */
    private suspend fun check(): Boolean? {
        if (!checking.tryLock()) return null
        return try {
            withTimeoutOrNull(CHECK_TIMEOUT_MILLIS) { watcher.check() }
        } finally {
            checking.unlock()
        }
    }

    private fun recordIdle(found: Boolean) {
        val idle = !found && settings.current.slowWhenIdle && power?.isInteractive == false
        val count = if (idle) (prefs.getInt(KEY_IDLE_CHECKS, 0) + 1).coerceAtMost(MAX_IDLE_CHECKS) else 0
        prefs.edit { putInt(KEY_IDLE_CHECKS, count) }
    }

    private fun resetIdle() = prefs.edit { putInt(KEY_IDLE_CHECKS, 0) }

    private fun nextDelayMillis(): Long =
        backoffDelay(settings.current.refreshIntervalMillis, prefs.getInt(KEY_IDLE_CHECKS, 0))

    private fun setAlarm(delayMillis: Long) {
        val manager = alarms ?: return
        val operation = alarmIntent(PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val at = SystemClock.elapsedRealtime() + delayMillis
        prefs.edit { putLong(KEY_NEXT_AT, at) }
        try {
            if (AlarmManagerCompat.canScheduleExactAlarms(manager)) {
                manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, operation)
                return
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "exact alarm denied", e)
        }
        // 精确闹钟权限被撤销（仅 Android 12 可能）时退回普通闹钟，系统可能推迟较久。
        manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, operation)
    }

    private fun cancelAlarm() {
        val operation = alarmIntent(PendingIntent.FLAG_NO_CREATE) ?: return
        alarms?.cancel(operation)
        operation.cancel()
    }

    private fun alarmIntent(flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, MessageCheckReceiver::class.java),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun scheduleJob() {
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, MessageCheckJobService::class.java))
            .setPeriodic(JOB_PERIOD_MILLIS)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        runCatching { jobs?.schedule(job) }.onFailure { Log.w(TAG, "unable to schedule job", it) }
    }

    private fun cancelJob() {
        jobs?.cancel(JOB_ID)
    }

    private fun cancelAll() {
        cancelAlarm()
        cancelJob()
    }

    private data class Plan(
        val enabled: Boolean,
        val powerSaving: Boolean,
        val intervalSeconds: Int,
        val slowWhenIdle: Boolean,
    )

    companion object {
        private const val TAG = "MessageScheduler"
        private const val JOB_ID = 1
        private const val JOB_PERIOD_MILLIS = 15 * 60_000L
        private const val CHECK_TIMEOUT_MILLIS = 30_000L
        private const val OVERDUE_GRACE_MILLIS = 60_000L
        private const val MAX_IDLE_CHECKS = 16
        private const val KEY_NEXT_AT = "next_alarm_at"
        private const val KEY_IDLE_CHECKS = "idle_checks"

        /** 息屏放慢后最长的检查间隔。 */
        const val IDLE_MAX_MILLIS = 5 * 60_000L

        /** 连续 [idleChecks] 次息屏且没有新消息时，间隔逐次翻倍，最多放慢到 [IDLE_MAX_MILLIS]，但不短于刷新间隔。 */
        internal fun backoffDelay(baseMillis: Long, idleChecks: Int): Long =
            if (idleChecks <= 0 || baseMillis >= IDLE_MAX_MILLIS) {
                baseMillis
            } else {
                (baseMillis shl idleChecks.coerceAtMost(MAX_IDLE_CHECKS)).coerceAtMost(IDLE_MAX_MILLIS)
            }
    }
}
