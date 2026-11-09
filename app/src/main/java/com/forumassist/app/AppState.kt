package com.forumassist.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.forumassist.app.data.AppLog
import com.forumassist.app.data.Board
import com.forumassist.app.data.BoardFilter
import com.forumassist.app.data.DefaultBoardOrders
import com.forumassist.app.data.Demo
import com.forumassist.app.data.ForumClient
import com.forumassist.app.data.Http
import com.forumassist.app.data.PinnedBoard
import com.forumassist.app.data.Settings
import com.forumassist.app.data.SignSupport
import com.forumassist.app.data.Site
import com.forumassist.app.data.SiteAdaptation
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
import kotlinx.coroutines.Deferred
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
        // 一次性清理：老版本内置、现已不再预置的站点（hostloc / 恩山 / 吾爱破解 / 演示站）。
        // 用户手动添加的站点不受影响，全部保留
        val legacy = listOf("hostloc.com", "right.com.cn", "52pojie.cn", "demo.forum")
        val loaded = store.sites()
        val kept = loaded.filterNot { s ->
            s.isDemo || legacy.any { s.baseUrl.contains(it, true) }
        }
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
                // 旧版本可能把发布页存成 www.xxx.com（没 scheme），刷新地址时会直接抛异常
                var v = s
                if (v.addressPage.isNotBlank() && !v.addressPage.startsWith("http", true)) {
                    changed = true
                    v = v.copy(addressPage = "https://" + v.addressPage.removePrefix("//"))
                }
                if (v.addressPage.isNotBlank()) v else {
                    val known = KNOWN_ADDRESS_PAGES.firstOrNull { (key, _) ->
                        s.baseUrl.contains(key, true)
                    }?.second
                    if (known == null) s else {
                        changed = true
                        s.copy(addressPage = known)
                    }
                }
            }
            if (changed) {
                sites.clear()
                sites.addAll(fixed)
                store.saveSites(sites)
            }
        }
        // 新增预设站点：老用户的站点列表非空不会走 defaultSites()，
        // 这里做一次性补齐（已删除过老王的用户会再加回来，可随时在「论坛」页停用）
        if (sites.none { isLaowang(it) }) {
            val lw = defaultSites().first { isLaowang(it) }
            sites.add(lw)
            store.saveSites(sites)
        }
        settings = store.settings()
        favorites.addAll(store.favorites())
        offline.addAll(store.offlineList())
        history.addAll(store.history())
        store.readMarks().entries.toList().takeLast(1_000).forEach { (key, value) -> readMarks[key] = value }

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
        // 三个分栏的磁盘缓存一起铺上，冷启动切分栏也是瞬时的
        for (i in 0..3) {
            val cached = store.feedCache(i)
            if (cached.isNotEmpty()) feedTabs[i] = cached
        }
        feedTabs[0]?.let { feed.addAll(it) }
        if (sites.any { it.enabled }) loadFeed(reset = true)

        AppLog.d(
            "应用",
            "启动 v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) 站点=${sites.size} " +
                "登录=${sites.count { it.loggedIn }} 分栏=$feedTab"
        )
        sites.forEach { s ->
            AppLog.d("应用", "站点 ${s.name} ${s.baseUrl} 登录=${s.loggedIn} 地址页=${s.addressPage}")
        }
    }

    /** 尚香书苑判定：名称含「尚香」或域名含 sxsy（地址发布页会换域名） */
    private fun isShangxiang(site: Site): Boolean =
        site.name.contains("尚香") || site.baseUrl.contains("sxsy", ignoreCase = true)

    /** 搜书吧判定：域名会换成 bew2459m / allshu 这类中转域，所以名称与多个域名特征都要认 */
    private fun isSoushu(site: Site): Boolean =
        site.name.contains("搜书吧") || site.baseUrl.contains("soushu", ignoreCase = true) ||
            site.baseUrl.contains("bew2459m", ignoreCase = true) ||
            site.baseUrl.contains("allshu", ignoreCase = true)

    /** 老王论坛判定：域名会换（laowang 系列域），名称与域名特征都要认 */
    private fun isLaowang(site: Site): Boolean =
        site.name.contains("老王") || site.baseUrl.contains("laowang", ignoreCase = true)

    /** 预设站点（老版本清理时只保留这些） */
    private fun isPresetSite(site: Site): Boolean =
        isShangxiang(site) || isSoushu(site) || isLaowang(site)

    private fun defaultSites(): List<Site> = listOf(
        Site(
            name = "尚香书苑",
            baseUrl = "https://sxsy33.com",
            kind = SiteKind.DISCUZ,
            color = 0xFF9C36B5.toInt(),
            // 域名经常更换，从地址发布页自动解析最新可用地址
            addressPage = "https://sxsy.org/"
        ),
        Site(
            name = "搜书吧",
            baseUrl = "https://nyt6y.bew2459m.com",
            kind = SiteKind.DISCUZ,
            color = 0xFF0CA678.toInt(),
            // 永久地址 → 中转域名 → 落地页「最新地址」，由 resolveAddressByChain 逐跳跟随
            addressPage = "http://www.soushu2030.com/"
        ),
        Site(
            name = "老王论坛",
            baseUrl = "https://laowang.vip",
            kind = SiteKind.DISCUZ,
            color = 0xFF1971C2.toInt()
            // 老王的「地址发布」是站内帖子而非独立发布页，域名失效时手动改站点地址即可
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

    /** 分栏下标 → guide 的 view 参数 */
    private fun viewOfTab(tab: Int): String = when (tab) {
        1 -> "newreply"
        2 -> "hot"
        3 -> "digest"
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

                client.resetListIssue()
                val blockedFidSet = enabled.flatMap { it.blockedFids }.toSet()
                // 快捷导航里的「永久地址」「每日签到」之类也带帖子链接，
                // 旧缓存里可能还留着，展示前统一剔掉
                val navNoise = setOf(
                    "永久地址", "每日签到", "积分充值", "排行榜", "淘帖", "搜索", "版规"
                )
                fun clean(list: List<ThreadSummary>) = list
                    .filterNot { it.title.trim() in navNoise }
                    .filterNot { blocked(it) }
                    // 置顶（版规/公告）不进信息流
                    .filterNot { it.isTop }
                    .filterNot { it.fid.isNotEmpty() && it.fid in blockedFidSet }
                    .distinctBy { it.key }
                    // 站点之间随机轮插：各站点内部保持时间倒序，但不同站点的帖子交错出现，
                    // 避免某个站点的帖子挤成一整块
                    .let(::interleaveBySite)

                // 三个分栏在同一个首页里，第一页只发一次请求，解析出三份列表
                var failed = 0
                var merged: List<ThreadSummary>
                if (page <= 1) {
                    val bundles = coroutineScope {
                        enabled.map { s ->
                            async(Dispatchers.IO) {
                                val now = System.currentTimeMillis()
                                // 站点没有门户四格时熔断 10 分钟，别每次切分栏都白下一遍首页
                                var b = if (now >= (portalCoolDown[s.id] ?: 0L)) {
                                    try {
                                        client.portalBundle(s).also {
                                            if (it.isEmpty()) portalCoolDown[s.id] = now + 10 * 60_000L
                                        }
                                    } catch (e: Exception) {
                                        AppLog.e("首页", "portalBundle 失败 ${s.name}", e)
                                        ForumClient.PortalBundle()
                                    }
                                } else ForumClient.PortalBundle()
                                // 首页只有四格的站点（搜书吧）解析失败就显示失败，
                                // 不能拿「导读」的另一批帖子顶上，否则内容和站点首页对不上
                                if (b.isEmpty() && !s.profile.portalOnly) {
                                    // 站点没有门户四格：只补当前分栏一次 guide，不重复发三次
                                    val view = viewOfTab(feedTab)
                                    val one = try {
                                        client.guide(s, view, 1)
                                    } catch (e: Exception) {
                                        AppLog.e("首页", "guide 失败 ${s.name}", e)
                                        emptyList()
                                    }
                                    b = when (view) {
                                        "newreply" -> b.copy(newreply = one)
                                        "hot" -> b.copy(hot = one)
                                        "digest" -> b.copy(digest = one)
                                        else -> b.copy(newthread = one)
                                    }
                                }
                                b
                            }
                        }.awaitAll()
                    }
                    // 整站一个分栏都没抓到，才算这个站点失败
                    failed = bundles.count { it.isEmpty() }
                    val perTab = (0..3).map { idx ->
                        clean(bundles.flatMap { it.of(viewOfTab(idx)) })
                    }.toMutableList()

                    // 四格可能只给了其中一两栏（比如没有「热帖」块）：
                    // 当前分栏空着就补一次 guide，别让用户切过来看到一片空白
                    if (perTab[feedTab].isEmpty() && bundles.any { !it.isEmpty() }) {
                        val fill = clean(
                            enabled.filterNot { it.profile.portalOnly }.flatMap { s ->
                                try {
                                    client.guide(s, viewOfTab(feedTab), 1)
                                } catch (e: Exception) {
                                    emptyList()
                                }
                            }
                        )
                        if (fill.isNotEmpty()) perTab[feedTab] = fill
                    }

                    perTab.forEachIndexed { idx, list ->
                        if (list.isNotEmpty()) {
                            feedTabs[idx] = list
                            store.saveFeedCache(list, idx)
                        }
                    }
                    merged = perTab[feedTab]
                    AppLog.d(
                        "首页",
                        "刷新完成 最新=${perTab[0].size} 回复=${perTab[1].size} 热帖=${perTab[2].size}"
                    )
                } else {
                    // 首页只有四格的站点（搜书吧）没有下一页：不能拿「导读」凑数，
                    // 否则翻出来的内容和首页完全不是一批帖子
                    val pageable = enabled.filterNot { it.profile.portalOnly }
                    if (pageable.isEmpty()) {
                        feedEnd = true
                        return@launch
                    }
                    merged = clean(
                        coroutineScope {
                            pageable.map { s ->
                                async(Dispatchers.IO) {
                                    try {
                                        client.guide(s, viewOfTab(feedTab), page)
                                    } catch (e: Exception) {
                                        emptyList()
                                    }
                                }
                            }.awaitAll()
                        }.flatten()
                    )
                }

                if (token != feedToken) return@launch

                // 用户可能在刷新途中切了分栏：以「当前分栏」为准展示，
                // 第一页已经把三个分栏都缓存好了，切换过去直接就有内容。
                val shownTab = feedTab
                if (page <= 1) merged = feedTabs[shownTab] ?: emptyList()

                val cached = if (merged.isEmpty()) {
                    store.feedCache(shownTab).filterNot { it.title.trim() in navNoise }
                } else emptyList()
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
                    feedTabs[shownTab] = merged
                    store.saveFeedCache(merged, shownTab)
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

                val issue = client.lastListIssue
                if (merged.isEmpty()) {
                    feedError = when {
                        cached.isNotEmpty() -> "站点暂时不可用，先显示上次缓存的内容"
                        usable.isNotEmpty() -> "真实站点暂时没有抓到内容，当前展示的是内置演示数据"
                        !issue.isNullOrBlank() -> issue
                        else -> "没有拿到内容。可能是站点需要登录、开启了防采集，或网络不通。\n试试在「论坛」页用网页模式打开。"
                    }
                } else if (failed > 0) {
                    // 有问题的站点优先报具体原因（登录失效 / 服务器错误），比「N 个站点失败」有用
                    feedError = client.lastListIssue ?: "$failed 个站点加载失败，已展示其余内容"
                }
                store.lastFeedRefresh = System.currentTimeMillis()
            } catch (e: Exception) {
                AppLog.e("首页", "刷新异常", e)
                feedError = "刷新失败：" + (e.message ?: e.javaClass.simpleName)
            } finally {
                if (token == feedToken) {
                    feedLoading = false
                    feedRefreshing = false
                }
            }
        }
    }

    /**
     * 站点间随机轮插：每个站点内部保持原顺序（时间倒序），
     * 站点之间的先后每次刷新随机洗一次，然后一轮一轮交错合并。
     * 两个站点时会得到 A1 B1 A2 B2 …，不会出现「前半屏全是 A 站」。
     */
    private fun interleaveBySite(list: List<ThreadSummary>): List<ThreadSummary> {
        if (list.size < 2) return list
        val groups = list.groupBy { it.siteId }
            .map { (_, items) -> items.iterator() }
            .toMutableList()
        groups.shuffle()
        val out = ArrayList<ThreadSummary>(list.size)
        var more = true
        while (more) {
            more = false
            for (iter in groups) {
                if (iter.hasNext()) {
                    out.add(iter.next())
                    more = true
                }
            }
        }
        return out
    }

    fun loadMoreFeed() {
        if (feedLoading || feedEnd) return
        feedPage += 1
        loadFeed(reset = false)
    }

    fun switchTab(index: Int) {
        if (feedTab == index) return
        feedTab = index
        // 第一页刷新会把三个分栏一起缓存，所以正常情况这里直接命中，切换是瞬时的
        val cached = feedTabs[index] ?: store.feedCache(index).also {
            if (it.isNotEmpty()) feedTabs[index] = it
        }
        if (cached.isNotEmpty()) {
            feed.clear()
            feed.addAll(cached)
            feedError = null
            feedEnd = false
            feedPage = 1
            // 不打断正在进行的刷新：它跑完会把当前分栏的内容补上
        } else {
            feed.clear()
            feedError = null
            // 已经有刷新在跑了就等它，别并发重复请求
            if (!feedLoading) loadFeed(reset = true)
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
            // 通用适配规则（SiteAdaptation）：基于首页 HTML 探测签到插件等特征，
            // 结果写入 site.signKind 随站点持久化，预设站点的独立开关不受影响
            val report = probe?.third?.let { runCatching { SiteAdaptation.analyze(it, kind) }.getOrNull() }
            val site = Site(
                name = name?.takeIf { it.isNotBlank() } ?: title ?: url.substringAfter("//"),
                baseUrl = url,
                kind = kind,
                color = pickColor(sites.size),
                signKind = report?.signPlugin.orEmpty()
            )
            sites.add(site)
            store.saveSites(sites)
            if (probe == null) {
                onDone(Result.failure(Exception("已保存，但暂时无法访问该站点，请检查网络或网址")))
            } else {
                report?.let {
                    AppLog.d("适配", "${site.name} → ${it.summary}")
                    notify("已适配：${it.summary}")
                }
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

    // ------------------------------------------------------------ 每日签到

    /** 该站点是否支持签到（按 SiteProfile.signKind 判定） */
    fun supportsSign(site: Site): Boolean = SignSupport.supported(site)

    /** 本地记录的「今天是否已签」（签到成功/已签时写入当天日期） */
    fun signedToday(site: Site): Boolean =
        store.signDate(site.id) == todayKey()

    private fun todayKey(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
            .format(java.util.Date())

    /** 探测签到状态（是否已签 / 要不要验证码），在 IO 线程执行 */
    fun probeSign(site: Site, onDone: (SignSupport.SignStatus) -> Unit) {
        scope.launch {
            val st = withContext(Dispatchers.IO) {
                runCatching { SignSupport.probe(site, client.httpClient) }
                    .getOrElse { SignSupport.SignStatus(note = it.message ?: "探测失败") }
            }
            onDone(st)
        }
    }

    /** 取签到图形验证码图片（尚香 sx_signin） */
    fun fetchSignCaptcha(site: Site, captchaUrl: String, onDone: (ByteArray?) -> Unit) {
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    client.httpClient.getBytes("$captchaUrl&t=${System.currentTimeMillis()}")
                }.getOrNull()
            }
            onDone(bytes)
        }
    }

    /** 执行签到；成功或「已签」都会把当天日期写进本地记录 */
    fun signIn(site: Site, seccode: String? = null, onDone: (SignSupport.SignResult) -> Unit) {
        if (!site.loggedIn) {
            onDone(SignSupport.SignResult.Failure("请先登录「${site.name}」（站点页 → 登录账号）"))
            return
        }
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { SignSupport.sign(site, client.httpClient, seccode) }
                    .getOrElse { SignSupport.SignResult.Failure(it.message ?: "签到异常") }
            }
            if (res is SignSupport.SignResult.Success || res is SignSupport.SignResult.Already) {
                store.saveSignDate(site.id, todayKey())
            }
            AppLog.d("签到", "${site.name} → $res")
            onDone(res)
        }
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
            // 声明了 chainPublisher 的站点（搜书吧）走多级跳转解析，其余站点保持原来的单页解析
            val resolved = if (site.profile.chainPublisher) {
                client.resolveAddressByChain(site.addressPage)
            } else {
                client.resolveAddress(site.addressPage)
            }
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
    /**
     * 回到首页时是否要自动刷新。
     * 只有距上次刷新超过 10 分钟才动：从正文退回来、切 tab 都不该把列表重拉一遍。
     * 下拉刷新走 refreshFeed()，不受这个限制。
     */
    fun maybeAutoRefresh(minIntervalMs: Long = 10 * 60 * 1000L) {
        if (feedLoading || feedRefreshing) return
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

    /**
     * 版块列表缓存：进正文再退回来不该重新请求，筛选条件也要原样保留。
     * key = siteId|fid|orderQuery|typeQuery，10 分钟内直接复用。
     */
    data class BoardPageCache(
        val items: List<ThreadSummary>,
        val orders: List<BoardFilter> = emptyList(),
        val types: List<BoardFilter> = emptyList(),
        val ts: Long = 0L,
        val scrollIndex: Int = 0
    )

    private val boardPageCache = mutableMapOf<String, BoardPageCache>()

    fun boardCacheKey(siteId: String, fid: String, orderQuery: String, typeQuery: String): String =
        "$siteId|$fid|$orderQuery|$typeQuery"

    fun cachedBoard(key: String): BoardPageCache? = boardPageCache[key]

    fun putBoard(key: String, entry: BoardPageCache) {
        boardPageCache[key] = entry
        // 别无限增长：超过 40 个按时间淘汰最旧的
        if (boardPageCache.size > 40) {
            boardPageCache.entries
                .sortedBy { it.value.ts }
                .take(boardPageCache.size - 40)
                .forEach { boardPageCache.remove(it.key) }
        }
    }

    /** 手动刷新某一页时把它的缓存丢掉，保证真的重新请求 */
    fun clearBoardPage(key: String) {
        boardPageCache.remove(key)
    }

    fun updateBoardScroll(key: String, index: Int) {
        boardPageCache[key]?.let { boardPageCache[key] = it.copy(scrollIndex = index) }
    }

    /** 清缓存时连板块列表缓存一起清掉 */
    fun clearBoardCache() = boardPageCache.clear()

    /**
     * 版块筛选记忆：key = siteId|fid → (排序 key, 分类 key)。
     * 进正文再退回来、或者从别的页面回到同一个版块，都还原上次的筛选，
     * 不会一回来就被重置成「全部」。
     */
    private val boardFilters = mutableMapOf<String, Pair<String, String>>()

    fun boardFilter(siteId: String, fid: String): Pair<String, String>? =
        boardFilters["$siteId|$fid"]

    fun saveBoardFilter(siteId: String, fid: String, orderKey: String, typeKey: String) {
        val key = "$siteId|$fid"
        if (orderKey.isBlank() && typeKey.isBlank()) boardFilters.remove(key)
        else boardFilters[key] = orderKey to typeKey
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

    /**
     * 同一条帖子的详情请求只发一次。
     *
     * 列表点开时就先发起（[prefetchDetail]），导航动画那两三百毫秒也用来等网络；
     * 详情页进来后 await 同一个 Deferred，不会重复打站点，也不会出现
     * 「列表点了、详情页又从头发一遍」的双份等待。
     */
    private val detailJobs = HashMap<String, Deferred<ThreadDetail?>>()

    fun detailDeferred(
        site: Site,
        tid: String,
        page: Int = 1,
        rawUrl: String? = null
    ): Deferred<ThreadDetail?> {
        val key = "${site.id}/$tid/$page"
        synchronized(detailJobs) {
            // 还在跑的请求直接复用；已经结束的（哪怕失败）重新发，重试才有意义
            detailJobs[key]?.let { if (!it.isCompleted) return it }
            val job = scope.async(Dispatchers.IO) {
                // 包一层：预取场景没人 await，异常不该冒到 Job 上
                runCatching { detail(site, tid, page, rawUrl) }.getOrNull()
            }
            detailJobs[key] = job
            job.invokeOnCompletion {
                synchronized(detailJobs) { if (detailJobs[key] === job) detailJobs.remove(key) }
            }
            return job
        }
    }

    /** 列表点开时预取正文：命中缓存、演示站、未开缓存的站点直接跳过 */
    fun prefetchDetail(t: ThreadSummary) {
        val site = site(t.siteId) ?: return
        if (site.isDemo || !site.profile.cacheDetail) return
        // 只能靠渲染的站点，HTTP 只有一成多的命中率，别提前抢带宽拖慢 WebView
        if (isRenderFirst(site.id)) return
        if (cacheGet("${site.id}/${t.tid}/1") != null) return
        detailDeferred(site, t.tid, 1, t.url)
    }

    /**
     * 详情页只能靠 JS 渲染的站点（链接由页面脚本临时加签名，HTTP 永远拿不到）。
     * 记住之后，下一条帖子直接让 WebView 起跑，不再先空等半秒 HTTP。
     */
    private val renderFirstSites = mutableSetOf<String>()

    fun markRenderFirst(siteId: String) {
        synchronized(renderFirstSites) { renderFirstSites.add(siteId) }
    }

    fun isRenderFirst(siteId: String): Boolean =
        synchronized(renderFirstSites) { siteId in renderFirstSites }

    /** 上次解析过的正文：进页面先秒开，再后台刷新（阅读页专用） */    suspend fun cachedDetail(site: Site, tid: String): ThreadDetail? = withContext(Dispatchers.IO) {
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

    /**
     * 搜索帖子标题。
     * @param siteId 非空时只搜这一个站点（「论坛 → 站点 → 搜索」就是这个用法），
     *               空值才跨全部已启用站点搜索（首页搜索）。
     */
    suspend fun search(query: String, siteId: String? = null): List<ThreadSummary> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            val targets = sites.filter { it.enabled && (siteId.isNullOrBlank() || it.id == siteId) }
            if (targets.isEmpty()) return@withContext emptyList()
            coroutineScope {
                targets.map { site ->
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
        readMarks.remove(t.key)
        readMarks[t.key] = t.replies
        while (readMarks.size > 1_000) {
            val oldest = readMarks.keys.firstOrNull() ?: break
            readMarks.remove(oldest)
        }
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
        // 内存里的正文 / 板块列表缓存也要一起清，否则「清完了还能秒开」会让人以为没生效
        synchronized(detailCache) { detailCache.clear() }
        clearBoardCache()
        boardCache.clear()
        feedTabs.clear()
        feed.clear()
        notify(if (ok) "缓存已清理（含正文与图片）" else "已清理，部分文件被占用")
    }

    private companion object {
        /**
         * 已知站点的「永久地址 / 地址发布页」。
         * key 用于匹配站点域名片段，老版本添加过的站点启动时自动补齐，换域名不用手动改。
         */
        val KNOWN_ADDRESS_PAGES = listOf(
            "sxsy" to "https://sxsy.org/",
            "soushu" to "http://www.soushu2030.com/",
            "bew2459m" to "http://www.soushu2030.com/",
            "allshu" to "http://www.soushu2030.com/"
        )
    }
}
