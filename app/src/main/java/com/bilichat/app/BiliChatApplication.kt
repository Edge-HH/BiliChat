package com.bilichat.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.bilichat.app.data.bili.BiliApi
import com.bilichat.app.notify.MessageMonitorService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class BiliChatApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
        container.appScope.launch {
            // 是否有已登录账号与“自动通知”设置共同决定后台服务是否运行。
            combine(container.accounts.state, container.settings.settings) { accounts, settings ->
                accounts.accounts.isNotEmpty() && settings.autoNotify
            }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) {
                        MessageMonitorService.start(this@BiliChatApplication)
                    } else {
                        MessageMonitorService.stop(this@BiliChatApplication)
                    }
                }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        // 图片请求不带 Cookie 与 Referer，避免触发图床防盗链。
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", BiliApi.USER_AGENT).build())
            }
            .build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .crossfade(true)
            .build()
    }
}
