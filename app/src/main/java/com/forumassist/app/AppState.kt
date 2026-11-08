package com.forumassist.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.forumassist.app.data.Board
import com.forumassist.app.data.Demo
import com.forumassist.app.data.ForumClient
import com.forumassist.app.data.Http
import com.forumassist.app.data.Settings
import com.forumassist.app.data.Site
import com.forumassist.app.data.SiteDetector
import com.forumassist.app.data.SiteKind
import com.forumassist.app.data.Store
import com.forumassist.app.data.ThreadDetail
import com.forumassist.app.data.ThreadSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppState(context: Context) {

    val store = Store(context)
    val http = Http(store)
    val client = ForumClient(http)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val sites: SnapshotStateList<Site> = mutableStateListOf()
    val feed: SnapshotStateList<ThreadSummary> = mutableStateListOf()
    val favorites: SnapshotStateList<ThreadSummary> = mutableStateListOf()
    val offline: SnapshotStateList<ThreadSummary> = mutableStateListOf()
    val history: SnapshotStateList<ThreadSummary> = mutableStateListOf()
    val readMarks = mutableStateMapOf<String, Int>()

    var settings by mutableStateOf(Settings())
        private set

    var feedLoading by mutableStateOf(false)
    var feedRefreshing by mutableStateOf(false)
    var feedError by mutableStateOf<String?>(null)
    var feedTab by mutableStateOf(0)
    var feedPage by mutableStateOf(1)
    var feedEnd by mutableStateOf(false)

    var toast by mutableStateOf<String?>(null)

    private val boardCache = mutableMapOf<String, List<Board>>()

    init {
        sites.addAll(store.sites())
        if (sites.isEmpty()) {
            sites.addAll(defaultSites())
            store.saveSites(sites)
        }
        settings = store.settings()
        favorites.addAll(store.favorites())
        offline.addAll(store.offlineList())
        history.addAll(store.history())
        readMarks.putAll(store.readMarks())
    }

    private fun defaultSites(): List<Site> = listOf(
        Demo.site(),
        Site(name = "全球主机交流", baseUrl = "https://hostloc.com", kind = SiteKind.DISCUZ,
            color = 0xFF4C6FFF.toInt()),
        Site(name = "恩山无线论坛", baseUrl = "https://www.right.com.cn/forum", kind = SiteKind.DISCUZ,
            color = 0xFFFF7A45.toInt()),
        Site(name = "吾爱破解", baseUrl = "https://www.52pojie.cn", kind = SiteKind.DISCUZ,
            color = 0xFFE03131.toInt())
    )

    // ------------------------------------------------------------ 工具

    fun siteName(id: String): String =
        sites.firstOrNull { it.id == id }?.name
            ?: if (id == Demo.SITE_ID) "演示论坛" else "未知站点"

    fun site(id: String): Site? = sites.firstOrNull { it.id == id }

    fun findThread(siteId: String, tid: String): ThreadSummary? =
        feed.firstOrNull { it.siteId == siteId && it.tid == tid }
            ?: favorites.firstOrNull { it.siteId == siteId && it.tid == tid }
            ?: offline.firstOrNull { it.siteId == siteId && it.tid == tid }
            ?: history.firstOrNull { it.siteId == siteId && it.tid == tid }

    fun isFav(key: String): Boolean = favorites.any { it.key == key }

    fun notify(msg: String) {
        toast = msg
    }

    private fun buildView(): String = when (feedTab) {
        1 -> "newreply"
        2 -> "hot"
        else -> "newthread"
    }

    private fun blocked(t: ThreadSummary): Boolean {
        val s = settings
        return s.blockedKeywords.any { it.isNotBlank() && t.title.contains(it, true) } ||
            s.blockedAuthors.any { it.isNotBlank() && t.author.contains(it, true) }
    }

    // ------------------------------------------------------------ 首页信息流

    fun loadFeed(reset: Boolean = true) {
        if (feedLoading) return
        scope.launch {
            feedLoading = true
            feedRefreshing = true
            feedError = null
            if (reset) {
                feedPage = 1
                feedEnd = false
            }
            val enabled = sites.filter { it.enabled }
            if (enabled.isEmpty()) {
                feed.clear()
                feedError = "还没有启用任何站点，去「论坛」里添加一个吧"
                feedLoading = false
                feedRefreshing = false
                return@launch
            }
            var failed = 0
            val page = feedPage
            val view = buildView()
            val collected = coroutineScope {
                enabled.map { site ->
                    async(Dispatchers.IO) {
                        val list = try {
                            if (site.isDemo) Demo.threads() else client.guide(site, view, page)
                        } catch (e: Exception) {
                            emptyList()
                        }
                        if (list.isEmpty() && !site.isDemo) failed++
                        list
                    }
                }.awaitAll()
            }.flatten()

            val merged = collected
                .filterNot { blocked(it) }
                .distinctBy { it.key }
                .sortedByDescending { it.ts }

            val usable = if (merged.isEmpty() && settings.demoFallback) {
                Demo.threads()
            } else merged

            if (reset) feed.clear()
            feed.addAll(usable.filter { t -> feed.none { it.key == t.key } })
            feedEnd = usable.isEmpty()

            if (merged.isEmpty()) {
                feedError = if (usable.isNotEmpty()) {
                    "真实站点暂时没有抓到内容，当前展示的是内置演示数据"
                } else {
                    "没有拿到内容。可能是站点需要登录、开启了防采集，或网络不通。\n试试在「论坛」页用网页模式打开。"
                }
            } else if (failed > 0) {
                feedError = "$failed 个站点加载失败，已展示其余内容"
            }
            store.lastFeedRefresh = System.currentTimeMillis()
            feedLoading = false
            feedRefreshing = false
        }
    }

    fun loadMoreFeed() {
        if (feedLoading || feedEnd) return
        feedPage += 1
        loadFeed(reset = false)
    }

    fun switchTab(index: Int) {
        if (feedTab == index) return
        feedTab = index
        feed.clear()
        loadFeed(reset = true)
    }

    // ------------------------------------------------------------ 站点

    fun addSite(rawUrl: String, name: String?, onDone: (Result<Site>) -> Unit) {
        val url = SiteDetector.normalizeUrl(rawUrl)
        if (url.isBlank()) {
            onDone(Result.failure(Exception("请输入论坛地址")))
            return
        }
        scope.launch {
            val probe = withContext(Dispatchers.IO) { http.probe(url) }
            val kind = probe?.first ?: SiteKind.DISCUZ
            val title = probe?.second
            val site = Site(
                name = name?.takeIf { it.isNotBlank() } ?: title ?: url.substringAfter("//"),
                baseUrl = url,
                kind = kind,
                color = pickColor(sites.size)
            )
            sites.add(site)
            store.saveSites(sites)
            if (probe == null) {
                onDone(Result.failure(Exception("已保存，但暂时无法访问该站点，请检查网络或网址")))
            } else {
                onDone(Result.success(site))
            }
        }
    }

    fun updateSite(site: Site) {
        val i = sites.indexOfFirst { it.id == site.id }
        if (i >= 0) {
            sites[i] = site
            store.saveSites(sites)
        }
    }

    fun deleteSite(site: Site) {
        sites.removeAll { it.id == site.id }
        store.saveSites(sites)
        boardCache.remove(site.id)
        feed.removeAll { it.siteId == site.id }
    }

    fun toggleSite(site: Site) {
        updateSite(site.copy(enabled = !site.enabled))
        loadFeed(reset = true)
    }

    private fun pickColor(index: Int): Int {
        val palette = listOf(
            0xFF4C6FFF, 0xFF12B886, 0xFFFF7A45, 0xFFE03131,
            0xFF9C36B5, 0xFF1098AD, 0xFFF08C00
        )
        return palette[index % palette.size].toInt()
    }

    suspend fun boards(site: Site): List<Board> = withContext(Dispatchers.IO) {
        if (site.isDemo) return@withContext Demo.boards()
        boardCache[site.id]?.let { return@withContext it }
        val list = client.boards(site)
        if (list.isNotEmpty()) boardCache[site.id] = list
        list
    }

    suspend fun threads(site: Site, fid: String, page: Int, orderBy: String = "lastpost"):
        List<ThreadSummary> = withContext(Dispatchers.IO) {
        if (site.isDemo) {
            val name = Demo.boards().firstOrNull { it.fid == fid }?.name
            return@withContext Demo.threads(name)
        }
        client.threads(site, fid, page, orderBy)
    }

    suspend fun detail(site: Site, tid: String, page: Int = 1): ThreadDetail? =
        withContext(Dispatchers.IO) {
            if (site.isDemo) return@withContext Demo.detail(tid)
            client.detail(site, tid, page) ?: store.loadOffline(site.id, tid)
        }

    suspend fun search(query: String): List<ThreadSummary> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        coroutineScope {
            sites.filter { it.enabled }.map { site ->
                async(Dispatchers.IO) {
                    try {
                        if (site.isDemo) Demo.search(q) else client.search(site, q)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }.distinctBy { it.key }.sortedByDescending { it.ts }
    }

    // ------------------------------------------------------------ 登录 / 发帖

    fun login(
        site: Site,
        username: String,
        password: String,
        captchaHash: String? = null,
        captchaCode: String? = null,
        onDone: (Result<String>) -> Unit
    ) {
        scope.launch {
            val res = if (site.isDemo) {
                if (username.isNotBlank() && password.isNotBlank()) Result.success(username)
                else Result.failure(Exception("演示站点随便填个账号密码即可"))
            } else client.login(site, username, password, captchaHash, captchaCode)
            res.onSuccess {
                updateSite(site.copy(username = username))
                notify("已登录：$username")
            }.onFailure { notify(it.message ?: "登录失败") }
            onDone(res)
        }
    }

    /** 取登录验证码图片（站点开启验证码时） */
    fun fetchLoginCaptcha(site: Site, onDone: (ForumClient.LoginCaptcha?) -> Unit) {
        scope.launch {
            val res = if (site.isDemo) null else client.loginCaptcha(site)
            onDone(res)
        }
    }

    fun logout(site: Site) {
        // 同时清掉原生存储与网页模式的 Cookie，避免残留旧会话
        http.clearAllCookiesFor(site)
        updateSite(site.copy(username = null))
        notify("已退出登录")
    }

    fun reply(site: Site, tid: String, fid: String, message: String, onDone: (Result<Unit>) -> Unit) {
        scope.launch {
            val res = if (site.isDemo) Result.success(Unit) else client.reply(site, tid, fid, message)
            res.onSuccess { notify("回复成功") }.onFailure { notify(it.message ?: "回复失败") }
            onDone(res)
        }
    }

    fun newThread(site: Site, fid: String, subject: String, message: String, onDone: (Result<Unit>) -> Unit) {
        scope.launch {
            val res = if (site.isDemo) Result.success(Unit) else client.newThread(site, fid, subject, message)
            res.onSuccess { notify("发布成功") }.onFailure { notify(it.message ?: "发布失败") }
            onDone(res)
        }
    }

    // ------------------------------------------------------------ 收藏 / 离线 / 已读

    fun toggleFav(t: ThreadSummary) {
        if (isFav(t.key)) {
            favorites.removeAll { it.key == t.key }
            notify("已取消收藏")
        } else {
            favorites.add(0, t)
            notify("已收藏")
        }
        store.saveFavorites(favorites)
    }

    fun download(t: ThreadSummary, onDone: (Boolean) -> Unit = {}) {
        scope.launch {
            val site = site(t.siteId)
            if (site == null) {
                notify("站点不存在")
                onDone(false)
                return@launch
            }
            if (offline.any { it.key == t.key }) {
                store.deleteOffline(t.siteId, t.tid)
                offline.removeAll { it.key == t.key }
                notify("已移除离线缓存")
                onDone(false)
                return@launch
            }
            notify("正在下载…")
            val detail = detail(site, t.tid)
            if (detail == null) {
                notify("下载失败，请稍后再试")
                onDone(false)
                return@launch
            }
            store.saveOffline(detail)
            offline.add(0, detail.summary)
            store.saveOfflineList(offline)
            notify("已离线保存：${detail.posts.size} 楼")
            onDone(true)
        }
    }

    fun isOffline(key: String): Boolean = offline.any { it.key == key }

    fun markRead(t: ThreadSummary) {
        readMarks[t.key] = t.replies
        store.saveReadMarks(readMarks)
        history.removeAll { it.key == t.key }
        history.add(0, t)
        while (history.size > 100) history.removeAt(history.size - 1)
        store.saveHistory(history)
    }

    fun newReplyCount(t: ThreadSummary): Int {
        val seen = readMarks[t.key] ?: return 0
        return (t.replies - seen).coerceAtLeast(0)
    }

    // ------------------------------------------------------------ 设置

    fun updateSettings(s: Settings) {
        settings = s
        store.saveSettings(s)
    }

    fun clearCache() {
        store.clearCache()
        offline.clear()
        history.clear()
        notify("缓存已清理")
    }
}
