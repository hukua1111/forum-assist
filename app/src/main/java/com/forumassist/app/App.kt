package com.forumassist.app

import android.content.Context
import coil.Coil
import coil.ImageLoader
import com.forumassist.app.data.Store

/**
 * 全局单例，避免引入更多 DI 依赖。
 */
object App {
    lateinit var state: AppState
        private set

    fun init(context: Context) {
        if (!::state.isInitialized) {
            state = AppState(context.applicationContext)

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
