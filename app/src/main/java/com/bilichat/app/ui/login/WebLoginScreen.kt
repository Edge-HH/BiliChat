package com.bilichat.app.ui.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bilichat.app.data.Cookies
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

private const val LOGIN_URL =
    "https://passport.bilibili.com/h5-app/passport/login?gourl=https%3A%2F%2Fm.bilibili.com%2F"

/**
 * 在内置浏览器中打开 B 站官方登录页，由用户自行完成登录；
 * 检测到登录 Cookie 后交给 [onCookies]。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebLoginScreen(
    onBack: () -> Unit,
    onCookies: (Map<String, String>) -> Unit,
    loggingIn: StateFlow<Boolean>,
) {
    val isLoggingIn by loggingIn.collectAsStateWithLifecycle()
    var progress by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    // 同一份登录凭据只尝试一次，失败后需要用户重新登录。
    var attemptedSession by remember { mutableStateOf<String?>(null) }
    val currentOnCookies by rememberUpdatedState(onCookies)
    val currentLoggingIn by rememberUpdatedState(isLoggingIn)

    fun checkCookies() {
        if (currentLoggingIn) return
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        val cookies = listOf("https://www.bilibili.com", "https://passport.bilibili.com")
            .mapNotNull { manager.getCookie(it) }
            .fold(emptyMap<String, String>()) { acc, header -> acc + Cookies.parse(header) }
        val complete = Cookies.isComplete(cookies)
        val session = cookies[Cookies.SESSION]
        if (complete && session != attemptedSession) {
            attemptedSession = session
            currentOnCookies(cookies)
        }
    }

    // 部分登录方式通过脚本跳转，定时兜底检查一次 Cookie。
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_500)
            checkCookies()
        }
    }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("登录哔哩哔哩") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.Outlined.Close, contentDescription = "关闭") }
                    },
                )
                if (progress in 1..99) {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                // 拦截 bilibili:// 等唤起 App 的链接。
                                val scheme = request.url.scheme
                                return scheme != "http" && scheme != "https"
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                checkCookies()
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                checkCookies()
                            }

                            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                checkCookies()
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        // 清除旧的网页登录状态，确保登录的是用户刚刚输入的账号。
                        cookieManager.removeAllCookies { loadUrl(LOGIN_URL) }
                        webView = this
                    }
                },
                onRelease = { view ->
                    view.stopLoading()
                    view.destroy()
                    webView = null
                },
            )

            if (isLoggingIn) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "正在登录…",
                            modifier = Modifier.padding(top = 16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}
