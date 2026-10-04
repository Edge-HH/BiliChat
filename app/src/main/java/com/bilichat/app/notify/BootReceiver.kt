package com.bilichat.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bilichat.app.BiliChatApplication

/** 开机或应用更新后，若开启了自动通知则重新安排后台检查。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        (context.applicationContext as BiliChatApplication).container.scheduler.ensure()
    }
}
