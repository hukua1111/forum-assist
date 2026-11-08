package com.forumassist.app

import android.content.Context
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
        }
    }

    fun store(context: Context): Store = Store(context.applicationContext)

    /** 回到前台时的按需刷新（未初始化则忽略） */
    fun maybeRefresh() {
        if (::state.isInitialized) state.maybeAutoRefresh()
    }
}
