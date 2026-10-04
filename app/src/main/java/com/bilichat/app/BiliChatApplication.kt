package com.bilichat.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.bilichat.app.data.bili.BiliApi
import okhttp3.OkHttpClient

class BiliChatApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
        container.scheduler.bind(container.appScope)
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
