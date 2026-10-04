package com.bilichat.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.bilichat.app.domain.model.Conversation
import com.bilichat.app.notify.MessageMonitorService
import com.bilichat.app.ui.BiliChatApp
import com.bilichat.app.ui.ChatTarget
import com.bilichat.app.ui.theme.BiliChatTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container get() = (application as BiliChatApplication).container

    /** 从通知或快捷方式打开的会话，由导航消费后置空。 */
    private val pendingChat = MutableStateFlow<ChatTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handleIntent(intent)
            lifecycleScope.launch { container.accounts.refreshAll() }
        }
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            BiliChatTheme(dynamicColor = settings.dynamicColor) {
                BiliChatApp(container = container, pendingChat = pendingChat)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 进程在后台被拉起时可能无法启动前台服务，回到前台时补启动。
        if (container.shouldMonitor()) MessageMonitorService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val mid = intent.getLongExtra(EXTRA_MID, 0L)
        val talkerId = intent.getLongExtra(EXTRA_TALKER_ID, 0L)
        if (mid != 0L && talkerId != 0L) {
            pendingChat.value = ChatTarget(mid, talkerId, intent.getIntExtra(EXTRA_SESSION_TYPE, Conversation.SESSION_USER))
        }
    }

    companion object {
        private const val EXTRA_MID = "mid"
        private const val EXTRA_TALKER_ID = "talker_id"
        private const val EXTRA_SESSION_TYPE = "session_type"

        fun intent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        /** 打开某个账号下的会话；若该账号不是当前账号会先切换过去。 */
        fun chatIntent(context: Context, mid: Long, talkerId: Long, sessionType: Int): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_MID, mid)
                .putExtra(EXTRA_TALKER_ID, talkerId)
                .putExtra(EXTRA_SESSION_TYPE, sessionType)
    }
}
