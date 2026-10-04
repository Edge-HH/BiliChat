package com.bilichat.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bilichat.app.BiliChatApplication
import kotlinx.coroutines.launch

/** 准时模式的闹钟：执行一次新消息检查并安排下一次。进程已被回收时由系统重新拉起。 */
class MessageCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as BiliChatApplication).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                container.scheduler.onAlarm()
            } finally {
                pending.finish()
            }
        }
    }
}
