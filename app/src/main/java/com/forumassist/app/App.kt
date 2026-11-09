package com.forumassist.app

import android.content.Context
import coil.Coil
import coil.ImageLoader
import com.forumassist.app.BuildConfig
import com.forumassist.app.data.Store

/**
 * 全局单例，避免引入更多 DI 依赖。
 */
object App {
    lateinit var state: AppState
        private set

    fun init(context: Context) {
        if (!::state.isInitialized) {
            com.forumassist.app.data.AppLog.init(context.applicationContext)
            state = AppState(context.applicationContext)

            // 版本升级后清一次图片缓存：修复防盗链之前，同一张附件图缓存下来的
            // 可能是站点的「绿色占位图」，URL 不变就会一直命中旧缓存
            runCatching {
                val sp = context.getSharedPreferences("forum_assist", Context.MODE_PRIVATE)
                if (sp.getInt("img_cache_ver", 0) != BuildConfig.VERSION_CODE) {
                    sp.edit().putInt("img_cache_ver", BuildConfig.VERSION_CODE).apply()
                    val loader = coil.Coil.imageLoader(context)
                    loader.memoryCache?.clear()
                    loader.diskCache?.clear()
                }
            }

            // 图片加载复用 App 的 OkHttp（带登录 Cookie + Referer），
            // 否则登录可见的附件图、开启防盗链的站点会 403 显示空白。
            runCatching {
                Coil.setImageLoader(
                    ImageLoader.Builder(context.applicationContext)
                        .okHttpClient(state.http.okHttpClient)
                        .build()
                )
            }
        }
    }

    fun store(context: Context): Store = Store(context.applicationContext)

    /** 回到前台时的按需刷新（未初始化则忽略） */
    fun maybeRefresh() {
        if (::state.isInitialized) state.maybeAutoRefresh()
    }
}
