package com.forumassist.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.forumassist.app.data.Board
import com.forumassist.app.data.DefaultBoardOrders
import com.forumassist.app.data.Demo
import com.forumassist.app.data.ForumClient
import com.forumassist.app.data.Http
import com.forumassist.app.data.PinnedBoard
import com.forumassist.app.data.Settings
import com.forumassist.app.data.Site
import com.forumassist.app.data.SiteDetector
import com.forumassist.app.data.SiteKind
import com.forumassist.app.data.Store
import com.forumassist.app.data.ThreadDetail
import com.forumassist.app.data.ThreadPage
import com.forumassist.app.data.ThreadSummary
import com.forumassist.app.data.boardOrderQuery
import com.forumassist.app.data.profile
import com.forumassist.app.data.withSections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class AppState(context: Context) {

    val store = Store(context)
    val http = Http(store)
    val client = ForumClient(http)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val sites: SnapshotStateList<Site> = mutableStateListOf()
    val feed: SnapshotStateList<ThreadSummary> = mutableStateListOf()

    /** 每个分栏抓到的内容缓存在内存里，切回来直接复用，不再重新请求 */
    private val feedTabs = mutableMapOf<Int, List<ThreadSummary>>()

    /** 当前刷新任务：切分栏/重新刷新时作废旧任务，避免旧结果覆盖新界面 */
    private var feedJob: Job? = null
    private var feedToken = 0

    val favorites: SnapshotStateList<ThreadSummary> = mutableStateListOf()
    /** 固定到底栏「板块」页的版块（快捷访问） */
    val pinnedBoards: SnapshotStateList<PinnedBoard> = mutableStateListOf()
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
        // 只保留「尚香书苑」：老版本装过的其他预设站点做一次性清理
        val loaded = store.sites()
        val kept = loaded.filter { isShangxiang(it) }
        if (kept.size != loaded.size) store.saveSites(kept)
        sites.addAll(kept)
        if (sites.isEmpty()) {
            sites.addAll(defaultSites())
            store.saveSites(sites)
        }
        // 老版本添加过的站点：补上已知的地址发布页（一次性迁移）
        run {
            var changed = false
            val fixed = sites.map { s ->
                if (s.addressPage.isBlank() && s.baseUrl.contains("sxsy", true)) {
                    changed = true
                    s.copy(addressPage = "https://sxsy.org/")
                } else s
            }
            if (changed) {
                sites.clear()
                sites.addAll(fixed)
                store.saveSites(sites)
            }
        }
        settings = store.settings()
        favorites.addAll(store.favorites())
        offline.addAll(store.offlineList())
        history.addAll(store.history())
        readMarks.putAll(store.readMarks())

        // 固定的版块：站点已被删除的条目一并清掉
        val ids = sites.map { it.id }.toSet()
        val storedPins = store.pinnedBoards()
        val pins = storedPins.filter { it.siteId in ids }
        pinnedBoards.addAll(pins.distinctBy { it.key })
        if (pins.size != storedPins.size) store.savePinnedBoards(pinnedBoards)

        // 上次是在网页模式里登录的：启动时把登录态补回来
        syncAllLoginStates()

        // 冷启动：先把上次的内容铺上（打开就有东西看），再在后台刷新一次。
        // 之后切页面/切分栏都只读缓存，不会自动刷。
        val cachedFeed = store.feedCache(0)
        if (cachedFeed.isNotEmpty()) {
            feedTabs[0] = cachedFeed
            feed.addAll(cachedFeed)
        }
        if (sites.any { it.enabled }) loadFeed(reset = true)
    }

    /** 尚香书苑判定：名称含「尚香」或域名含 sxsy（地址发布页会换域名） */
    private fun isShangxiang(site: Site): Boolean =
        site.name.contains("尚香") || site.baseUrl.contains("sxsy", ignoreCase = true)

    private fun defaultSites(): List<Site> = listOf(
        Site(
            name = "尚香书苑",
            baseUrl = "https://sxsy33.com",
            kind = SiteKind.DISCUZ,
            color = 0xFF9C36B5.toInt(),
            // 域名经常更换，从地址发布页自动解析最新可用地址
            addressPage = "https://sxsy.org/"
        )
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

    // ------------------------------------------------------------ 登录态同步（网页 → 原生）

    /**
     * 用 Cookie 存储里的真实登录态校正 site.loggedIn。
     * 网页模式登录成功后，Discuz 会写入 `<前缀>_auth` Cookie；只要它存在就算已登录，
     * 这样发帖/回帖就不会再提示「需要登录」，退出网页登录后也会自动变回未登录。
     */
    fun syncLoginState(site: Site) {
        val host = runCatching { site.baseUrl.toHttpUrlOrNull()?.host }.getOrNull() ?: return
        val auth = store.cookies().firstOrNull {
            it.host == host && it.name.endsWith("_auth") && it.value.isNotBlank()
        }
        val shouldLogin = auth != null
        if (shouldLogin == site.loggedIn) return
        if (shouldLogin) {
            updateSite(site.copy(cookie = "${auth!!.name}=${auth.value}", username = site.username ?: "网页账号"))
        } else {
            updateSite(site.copy(cookie = null))
        }
    }

    fun syncAllLoginStates() {
        sites.toList().forEach { syncLoginState(it) }
    }

    // ------------------------------------------------------------ 板块固定（快捷访问）

    fun isPinned(siteId: String, fid: String): Boolean =
        pinnedBoards.any { it.siteId == siteId && it.fid == fid }

    /** 固定 / 取消固定版块到「板块」页 */
    fun togglePin(site: Site, board: Board) {
        val exist = pinnedBoards.firstOrNull { it.siteId == site.id && it.fid == board.fid }
        if (exist != null) {
            pinnedBoards.removeAll { it.key == exist.key }
            notify("已取消固定：${board.name}")
        } else {
            pinnedBoards.add(PinnedBoard(siteId = site.id, fid = board.fid, name = board.name))
            notify("已固定到底栏：${board.name}")
        }
        store.savePinnedBoards(pinnedBoards)
    }

    fun unpinBoard(key: String) {
        val hit = pinnedBoards.firstOrNull { it.key == key } ?: return
        pinnedBoards.removeAll { it.key == key }
        store.savePinnedBoards(pinnedBoards)
        notify("已取消固定：${hit.name}")
    }

    fun notify(msg: String) {
        toast = msg
    }

    /** 站点首页抓取失败后短暂熔断，避免每次刷新都撞同一个错误页 */
    private val portalCoolDown = mutableMapOf<String, Long>()

    /** 地址发布页解析节流 */
    private val addressTried = mutableMapOf<String, Long>()
    private var resolvingAddress = false

    private fun buildView(tab: Int = feedTab): String = when (tab) {
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

    /**
     * @param reset true=重新抓第一页；false=加载下一页
     *
     * 只有「冷启动」「下拉刷新」「点刷新按钮」会走到这里；
     * 切换分栏/切换页面只读缓存，不会自动重新请求。
     */
    fun loadFeed(reset: Boolean = true) {
        if (!reset && feedLoading) return
        if (reset) {
            feedJob?.cancel()
            feedPage = 1
            feedEnd = false
        }
        val tab = feedTab
        val page = feedPage
        val token = ++feedToken
        // 同步置位，避免首页的 LaunchedEffect 看到 false 又发一次请求
        feedLoading = true
        feedRefreshing = true
        if (reset) feedError = null

        feedJob = scope.launch {
            try {
                val enabled = sites.filter { it.enabled }
                if (enabled.isEmpty()) {
                    feed.clear()
                    feedError = "还没有启用任何站点，去「论坛」里添加一个吧"
                    return@launch
                }
                var failed = 0
                val view = buildView(tab)
                val collected = coroutineScope {
                    enabled.map { site ->
                        async(Dispatchers.IO) {
                            val list = try {
                                if (site.isDemo) {
                                    Demo.threads()
                                } else if (page <= 1) {
                                    // 优先读站点首页自带的门户四格；失败则熔断 10 分钟并回退 guide
                                    val now = System.currentTimeMillis()
                                    val portal = if (now >= (portalCoolDown[site.id] ?: 0L)) {
                                        client.portal(site, view).also {
                                            if (it.isEmpty()) portalCoolDown[site.id] = now + 10 * 60_000L
                                        }
                                    } else emptyList()
                                    portal.ifEmpty { client.guide(site, view, page) }
                                } else {
                                    client.guide(site, view, page)
                                }
                            } catch (e: Exception) {
                                emptyList()
                            }
                            if (list.isEmpty() && !site.isDemo) failed++
                            list
                        }
                    }.awaitAll()
                }.flatten()

                val blockedFidSet = enabled.flatMap { it.blockedFids }.toSet()
                val merged = collected
                    .filterNot { blocked(it) }
                    // 置顶（版规/公告）不进信息流
                    .filterNot { it.isTop }
                    .filterNot { it.fid.isNotEmpty() && it.fid in blockedFidSet }
                    .distinctBy { it.key }
                    .sortedByDescending { it.ts }

                if (token != feedToken || tab != feedTab) return@launch

                val cached = if (merged.isEmpty()) store.feedCache(tab) else emptyList()
                val usable = when {
                    merged.isNotEmpty() -> merged
                    cached.isNotEmpty() -> cached
                    // 演示数据只在还有「演示站点」时兜底，否则会点不进正文
                    settings.demoFallback && sites.any { it.isDemo } -> Demo.threads()
                    else -> emptyList()
                }

                if (reset) {
                    // 抓不到内容时保留旧列表，别把已经读出来的内容清空
                    if (usable.isNotEmpty()) {
                        feed.clear()
                        feed.addAll(usable.distinctBy { it.key })
                    }
                } else {
                    feed.addAll(usable.filter { t -> feed.none { it.key == t.key } })
                }
                feedEnd = usable.isEmpty()

                if (merged.isNotEmpty()) {
                    feedTabs[tab] = merged
                    store.saveFeedCache(merged, tab)
                }

                // 全部失败时，尝试从「地址发布页」找最新域名（每站 30 分钟内只试一次）
                if (merged.isEmpty()) {
                    val now = System.currentTimeMillis()
                    enabled.filter { it.addressPage.isNotBlank() }.forEach { s ->
                        if (now - (addressTried[s.id] ?: 0L) > 30 * 60_000L) {
                            addressTried[s.id] = now
                            refreshAddress(s, silent = true)
                        }
                    }
                }

                if (merged.isEmpty()) {
                    feedError = if (cached.isNotEmpty()) {
                        "站点暂时不可用（服务器报错或限流），先显示上次缓存的内容"
                    } else if (usable.isNotEmpty()) {
                        "真实站点暂时没有抓到内容，当前展示的是内置演示数据"
                    } else {
                        "没有拿到内容。可能是站点需要登录、开启了防采集，或网络不通。\n试试在「论坛」页用网页模式打开。"
                    }
                } else if (failed > 0) {
                    feedError = "$failed 个站点加载失败，已展示其余内容"
                }
                store.lastFeedRefresh = System.currentTimeMillis()
            } finally {
                if (token == feedToken) {
                    feedLoading = false
                    feedRefreshing = false
                }
            }
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
        // 先看内存缓存，再看磁盘缓存；都没有才请求。
        val cached = feedTabs[index] ?: store.feedCache(index).also {
            if (it.isNotEmpty()) feedTabs[index] = it
        }
        if (cached.isNotEmpty()) {
            feedJob?.cancel()
            feed.clear()
            feed.addAll(cached)
            feedError = null
            feedEnd = false
            feedPage = 1
            feedLoading = false
            feedRefreshing = false
        } else {
            feed.clear()
            loadFeed(reset = true)
        }
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
        // 站点没了，固定在该站点的快捷入口也一并清掉
        if (pinnedBoards.any { it.siteId == site.id }) {
            pinnedBoards.removeAll { it.siteId == site.id }
            store.savePinnedBoards(pinnedBoards)
        }
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

    suspend fun allBoards(site: Site): List<Board> = withContext(Dispatchers.IO) {
        if (site.isDemo) return@withContext Demo.boards()
        val cached = boardCache[site.id]
        if (cached != null) return@withContext cached
        client.boards(site).also { if (it.isNotEmpty()) boardCache[site.id] = it }
    }

    suspend fun boards(site: Site): List<Board> = withContext(Dispatchers.IO) {
        // 屏蔽的版块（多为广告版块）不进入常规列表
        allBoards(site).filterNot { it.fid in site.blockedFids }
    }

    /** 屏蔽 / 恢复版块 */
    fun toggleBoardBlocked(site: Site, fid: String) {
        val blocked = site.blockedFids.toMutableList()
        if (fid in blocked) blocked.remove(fid) else blocked.add(fid)
        updateSite(site.copy(blockedFids = blocked))
        loadFeed(reset = true)
        notify(if (fid in blocked) "已屏蔽该版块" else "已恢复该版块")
    }

    /**
     * 从站点配置的「地址发布页」解析最新可用域名并切换。
     * silent=true 时不弹成功提示（用于后台自动纠错）。
     */
    fun refreshAddress(site: Site, silent: Boolean = false, onDone: ((String?) -> Unit)? = null) {
        if (site.addressPage.isBlank()) {
            if (!silent) notify("该站点还没有配置地址发布页")
            onDone?.invoke(null)
            return
        }
        if (resolvingAddress) { onDone?.invoke(null); return }
        scope.launch {
            resolvingAddress = true
            val resolved = client.resolveAddress(site.addressPage)
            resolvingAddress = false
            if (resolved != null && resolved.trimEnd('/') != site.baseUrl.trimEnd('/')) {
                updateSite(site.copy(baseUrl = resolved))
                notify("已切换到最新地址：" + resolved.removePrefix("https://"))
                boardCache.remove(site.id)
                loadFeed(reset = true)
            } else if (!silent) {
                notify(if (resolved == null) "没能从地址发布页解析到可用地址" else "当前已是最新地址")
            }
            onDone?.invoke(resolved)
        }
    }

    /**
     * 回到前台/切回首页时不再自动刷新——已经读出来的内容就留着，
     * 只有列表为空（冷启动没缓存、或上次全失败）才补一次。
     */
    fun maybeAutoRefresh(minIntervalMs: Long = 3 * 60 * 1000L) {
        if (feedLoading || feedRefreshing) return
        if (feed.isNotEmpty()) return
        if (System.currentTimeMillis() - store.lastFeedRefresh < minIntervalMs) return
        loadFeed(reset = true)
    }

    suspend fun threads(site: Site, fid: String, page: Int, orderBy: String = "lastpost"):
        List<ThreadSummary> = threadPage(site, fid, page, boardOrderQuery(orderBy)).threads

    /** 版块列表 + 页面自带的分类筛选（排序固定，分类从页面解析） */
    suspend fun threadPage(
        site: Site,
        fid: String,
        page: Int,
        orderQuery: String = "",
        typeQuery: String = ""
    ): ThreadPage = withContext(Dispatchers.IO) {
        if (site.isDemo) {
            val name = Demo.boards().firstOrNull { it.fid == fid }?.name
            return@withContext ThreadPage(
                threads = Demo.threads(name),
                orders = DefaultBoardOrders
            )
        }
        client.threadPage(site, fid, page, orderQuery, typeQuery)
    }

    /** 详情抓取失败时的诊断信息（展示在详情页错误提示里，便于排查） */
    var lastDetailError: String? = null
        private set

    /**
     * 正文短时内存缓存：只有开启 profile.cacheDetail 的站点（目前是尚香书苑）使用。
     * 从列表返回再进同一帖可直接命中，省掉整轮「网络 + Jsoup 解析」。
     */
    private val detailCache = object : LinkedHashMap<String, Pair<Long, ThreadDetail>>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Pair<Long, ThreadDetail>>
        ): Boolean = size > 12
    }

    private fun cacheGet(key: String): ThreadDetail? {
        val e = synchronized(detailCache) { detailCache[key] } ?: return null
        if (System.currentTimeMillis() - e.first > 10 * 60_000L) return null
        return e.second
    }

    private fun cachePut(key: String, detail: ThreadDetail) {
        synchronized(detailCache) { detailCache[key] = System.currentTimeMillis() to detail }
    }

    suspend fun detail(site: Site, tid: String, page: Int = 1, rawUrl: String? = null): ThreadDetail? =
        withContext(Dispatchers.IO) {
            if (site.isDemo) return@withContext Demo.detail(tid)
            val useCache = site.profile.cacheDetail
            val key = "${site.id}/$tid/$page"
            if (useCache) cacheGet(key)?.let { return@withContext it }

            val online = client.detail(site, tid, page, rawUrl)
            if (online == null) {
                lastDetailError = client.lastDetailDebug
                store.loadOffline(site.id, tid)?.withSections()
            } else {
                // 段落预切好，阅读页就不用再在主线程上排版几万字
                val ready = online.withSections()
                if (useCache) {
                    cachePut(key, ready)
                    // 落盘放到后台，别把落盘时间算进「打开正文」的等待里
                    scope.launch(Dispatchers.IO) { store.saveDetailCache(ready) }
                }
                ready
            }
        }

    /** 上次解析过的正文：进页面先秒开，再后台刷新（阅读页专用） */
    suspend fun cachedDetail(site: Site, tid: String): ThreadDetail? = withContext(Dispatchers.IO) {
        if (!site.profile.cacheDetail) return@withContext null
        val key = "${site.id}/$tid/1"
        cacheGet(key) ?: store.loadDetailCache(site.id, tid)?.withSections()?.also { cachePut(key, it) }
    }

    /** WebView 渲染兜底：用取回的 DOM 解析详情（Jsoup 解析放到后台线程） */
    suspend fun detailFromHtml(site: Site, tid: String, html: String, page: Int = 1): ThreadDetail? =
        withContext(Dispatchers.Default) {
            val parsed = client.parseDetailHtml(site, tid, page, html) ?: return@withContext null
            val ready = parsed.withSections()
            if (site.profile.cacheDetail) {
                cachePut("${site.id}/$tid/$page", ready)
                scope.launch(Dispatchers.IO) { store.saveDetailCache(ready) }
            }
            ready
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
        // cookie 字段是「已登录」标记，必须一起清掉，否则界面还显示已登录
        updateSite(site.copy(username = null, cookie = null))
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

    /** 是否读过（列表里据此置灰）；设置里可以关掉这个效果 */
    fun isRead(key: String): Boolean = settings.readGray && readMarks.containsKey(key)

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
        val ok = store.clearCache()
        offline.clear()
        history.clear()
        // 内存里的正文缓存也要一起清，否则「清完了还能秒开」会让人以为没生效
        synchronized(detailCache) { detailCache.clear() }
        notify(if (ok) "缓存已清理（含正文与图片）" else "已清理，部分文件被占用")
    }
}
