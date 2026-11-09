package com.forumassist.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Discuz / PHPWind 系论坛的抓取与解析。
 * 采用「多套选择器逐步降级」的策略，尽量兼容不同模板（含手机版 mobile=2）。
 */
class ForumClient(private val http: Http) {

    /** 附件下载等场景需要直接复用带 Cookie 的 OkHttp 客户端 */
    val httpClient: Http get() = http

    private fun u(site: Site, path: String): String =
        site.baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    private fun pageUrl(site: Site, path: String, page: Int): String {
        // 解析统一走电脑版模板（手机模板缺四格/附件等结构）；网页模式仍由 WebView 用移动 UA
        val base = u(site, path)
        if (page <= 1) return base
        return base + (if (base.contains("?")) "&" else "?") + "page=$page"
    }

    // ---------------------------------------------------------------- 版块

    suspend fun boards(site: Site): List<Board> = withContext(Dispatchers.IO) {
        lastListIssue = null
        val html = http.get(pageUrl(site, "forum.php", 1), referer = u(site, "forum.php"))
        if (html == null) {
            lastListIssue = "请求失败：网络不通或站点无响应"
            return@withContext emptyList()
        }
        if (isServerErrorPage(html)) {
            lastListIssue = "站点返回错误页（服务器过载或数据库异常）"
            return@withContext emptyList()
        }
        if (isLoginLanding(html)) {
            lastListIssue = "登录态已失效（站点跳到了登录页）。去「论坛」页用网页模式重新登录一次即可"
            return@withContext emptyList()
        }
        val doc = Jsoup.parse(html, site.baseUrl)
        val map = LinkedHashMap<String, Board>()
        doc.select("a[href*=forumdisplay][href*=fid=]").forEach { a ->
            val fid = Regex("fid=(\\d+)").find(a.attr("href"))?.groupValues?.get(1) ?: return@forEach
            var name = a.text().trim()
            if (name.isBlank()) name = a.attr("title").trim()
            if (name.length < 2 || name.length > 30) return@forEach
            if (name in setOf("版块", "论坛", "返回", "更多")) return@forEach
            if (map.containsKey(fid)) return@forEach
            val box = a.closest("li, tr, dl, dd, .bm_c") ?: a.parent()
            val desc = box?.selectFirst(".xs, .xw0, .desc, em")?.text()?.trim().orEmpty()
            map[fid] = Board(siteId = site.id, fid = fid, name = name, desc = desc.take(40))
        }
        AppLog.d("版块", "解析到 ${map.size} 个版块")
        map.values.toList()
    }

    // ---------------------------------------------------------------- 列表

    /**
     * 最近一次列表抓取遇到的问题（需要登录 / 服务器错误 / 结构不认识）。
     * UI 直接展示，用户一看就知道是「登录掉了」还是「站点挂了」。
     */
    var lastListIssue: String? = null
        private set

    /** 一次批量抓取开始前清空上轮的问题记录 */
    fun resetListIssue() {
        lastListIssue = null
    }

    /**
     * 是否被弹到了登录页。
     *
     * 尚香书苑游客访问 forumdisplay 会 302 到 member.php?mod=logging&action=login，
     * 返回的是登录页——里面的「永久地址」「每日签到」都是快捷导航里的帖子链接，
     * 以前会被当成帖子解析出来，于是首页只显示一条「永久地址」。
     */
    private fun isLoginLanding(html: String): Boolean {
        if (html.contains("mod=logging&action=login") && html.contains("没有账号")) return true
        if (html.contains("id=\"loginform\"") || html.contains("name=\"loginsubmit\"")) return true
        return false
    }

    suspend fun guide(site: Site, view: String, page: Int): List<ThreadSummary> =
        withContext(Dispatchers.IO) {
            // 「最新回复」在 Discuz 导读里的 view 是 new，个别模板不认 newreply（会退回热门）。
            // 只有 profile 显式声明的站点才替换，其余站点沿用调用方传入的 view。
            val profile = site.profile
            val guideView = profile.guideReplyView.takeIf { it.isNotBlank() && view == "newreply" } ?: view
            val url = pageUrl(site, "forum.php?mod=guide&view=$guideView", page)
            var html = http.get(url, desktop = true)
            if (html == null || isServerErrorPage(html)) html = http.get(url)
            if (html == null) {
                lastListIssue = "请求失败（网络超时或无响应）"
                return@withContext emptyList()
            }
            if (isServerErrorPage(html)) {
                lastListIssue = "站点返回错误页（可能是服务器过载或数据库异常）"
                return@withContext emptyList()
            }
            if (isLoginLanding(html)) {
                lastListIssue = "登录态已失效（站点跳到了登录页）。去「论坛」页用网页模式重新登录一次即可"
                return@withContext emptyList()
            }

            // 手机模板常常没有 guide 模块，会重定向回首页；此时解析出来的只是广告位里的几条公告，
            // 不能当成信息流，直接判空交给上层（门户四格/缓存）处理。
            if (html.contains("ma-text-grid") || html.contains("id=\"tabs-box\"")) {
                return@withContext emptyList()
            }
            // guide 页必须真的有帖子列表（#threadlist），否则就是被重定向到首页了
            val doc = Jsoup.parse(html, site.baseUrl)
            if (doc.selectFirst("#threadlist, tbody[id^=normalthread], .bm_c table") == null) {
                lastListIssue = "guide 列表页结构不认识（可能被重定向到首页）"
                return@withContext emptyList()
            }
            parseThreads(html, site)
        }

    // ------------------------------------------------- 地址发布页解析

    /** 发布页里常见的第三方域名，避免把社群/统计链接误当成站点地址 */
    private val NOISE_HOSTS = listOf(
        "t.me", "telegram.me", "telegram.org", "telegram.dog",
        "w3.org", "google.com", "googleapis.com", "gstatic.com", "google-analytics.com",
        "cloudflare.com", "cloudflareinsights.com", "jsdelivr.net", "unpkg.com", "bootcdn.net",
        "github.com", "githubusercontent.com", "mozilla.org", "adobe.com",
        "baidu.com", "cnzz.com", "umeng.com", "weixin.qq.com", "qq.com",
        "facebook.com", "twitter.com", "x.com", "youtube.com", "youtu.be", "instagram.com",
        "microsoft.com", "apple.com"
    )

    private fun isNoiseHost(host: String): Boolean {
        val h = host.lowercase().removePrefix("www.")
        return NOISE_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    /**
     * 从「地址发布页」解析站点最新可用域名。通用策略（不针对单一站点）：
     *  1) 发布页的 JS 配置项（latestDomain / websiteUrl / siteUrl 等）——多数发布页的数据源
     *  2) 页面链接（「进入 / 访问」按钮优先）与明文域名（含 <script> 里的文本）
     *  3) base64 混淆内容里解码出的域名
     * 候选按优先级逐个探测，命中论坛特征才采用，避免把发布页自身/镜像页/邮箱域名当站点。
     */
    suspend fun resolveAddress(pageUrl0: String): String? = withContext(Dispatchers.IO) {
        val pageUrl = pageUrl0.trim().let {
            if (it.startsWith("http", true)) it else "https://" + it.removePrefix("//")
        }
        val html = run {
            var h = http.get(pageUrl)
            if (h == null && pageUrl.startsWith("https://", true)) {
                // 有些发布页只开了 http
                h = http.get("http://" + pageUrl.removePrefix("https://"))
            }
            h
        } ?: return@withContext null
        val pageHost = runCatching { java.net.URL(pageUrl).host }.getOrNull().orEmpty().lowercase()

        val high = LinkedHashSet<String>()
        val mid = LinkedHashSet<String>()
        val low = LinkedHashSet<String>()
        val hits = HashMap<String, Int>()

        fun normalize(raw: String): String? {
            var u = raw.trim().trim('"', '\'', ' ', ',', ';', '，', '。')
            if (u.isEmpty() || u.contains(" ") || u.contains("@")) return null
            if (!u.startsWith("http", true) &&
                (u.startsWith("mailto:", true) || u.startsWith("javascript:", true) || u.startsWith("tel:", true))
            ) return null
            if (!u.startsWith("http", true)) {
                if (!u.contains(".")) return null
                u = "https://$u"
            }
            val host = runCatching { java.net.URL(u).host }.getOrNull()?.lowercase()?.trim() ?: return null
            if (host.isBlank() || !host.contains(".")) return null
            if (host == pageHost || isNoiseHost(host)) return null
            return "https://$host"
        }

        fun add(raw: String, bucket: MutableSet<String>) {
            val root = normalize(raw) ?: return
            bucket.add(root)
            hits[root] = (hits[root] ?: 0) + 1
        }

        // 1) JS 配置里的“最新地址”字段（本次这份发布页就是 CONFIG.latestDomain / websiteUrl）
        Regex(
            "[\"']?(latest_?domain|latest_?url|latest_?addr(?:ess)?|website_?url|site_?url|official_?url|home_?page)" +
                "[\"']?\\s*[:=]\\s*[\"']([^\"'\\s]{4,120})[\"']",
            RegexOption.IGNORE_CASE
        ).findAll(html).take(40).forEach { add(it.groupValues[2], high) }

        // 通用字段名（url / domain）可信度低，只作为普通候选
        Regex("[\"']?(?:url|domain|host)[\"']?\\s*[:=]\\s*[\"']([^\"'\\s]{4,120})[\"']", RegexOption.IGNORE_CASE)
            .findAll(html).take(40).forEach { add(it.groupValues[1], mid) }

        // 2) 链接：文案为「进入 / 访问 / enter」的优先
        val doc = Jsoup.parse(html, pageUrl)
        doc.select("a[href]").forEach { a ->
            val rawHref = a.attr("href").trim()
            if (rawHref.startsWith("mailto:", true) ||
                rawHref.startsWith("javascript:", true) ||
                rawHref.startsWith("tel:", true) || rawHref.startsWith("#")
            ) return@forEach
            val hint = (a.text() + " " + a.attr("id") + " " + a.attr("class")).lowercase()
            val preferred = hint.contains("进入") || hint.contains("访问") ||
                hint.contains("enter") || hint.contains("domain") || hint.contains("button")
            add(a.absUrl("href"), if (preferred) high else mid)
        }

        // 3) 全文域名（含 <script> 内配置文本）
        Regex("https?://((?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,10})")
            .findAll(html).take(300).forEach { add("https://" + it.groupValues[1], mid) }

        // 4) 纯文本域名（邮箱域名用 lookbehind 排除）
        Regex("(?<![@\\w.-])((?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,6})(?![\\w.-])")
            .findAll(doc.text()).take(200).forEach { add("https://" + it.groupValues[1], mid) }

        // 5) base64 混淆
        Regex("[A-Za-z0-9+/]{40,}={0,2}").findAll(html).take(20).forEach { m ->
            runCatching {
                val decoded = String(android.util.Base64.decode(m.value, android.util.Base64.DEFAULT))
                Regex("(?:https?://)?((?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,10})")
                    .findAll(decoded).take(5).forEach { d -> add(d.groupValues[1], low) }
            }
        }

        // 出现次数多的更可能是站点主域名
        val ordered = high.toList() +
            mid.sortedByDescending { hits[it] ?: 0 } +
            low.sortedByDescending { hits[it] ?: 0 }

        for (root in ordered.distinct().take(14)) {
            val probe = http.get("$root/") ?: continue
            if (looksLikeForum(probe)) return@withContext root
            if (root.startsWith("https://")) {
                val plain = "http://" + root.removePrefix("https://")
                val alt = http.get("$plain/")
                if (alt != null && looksLikeForum(alt)) return@withContext plain
            }
        }
        null
    }

    /** 粗略判断是不是论坛页面（Discuz 特征 / 站点标题 / 登录页） */
    private fun looksLikeForum(html: String): Boolean {
        if (html.length < 600) return false
        val h = html.lowercase()
        return h.contains("discuz") || h.contains("forum.php") || h.contains("viewthread") ||
            h.contains("member.php?mod=logging") || h.contains("formhash") ||
            html.contains("尚香") || (h.contains("powered by") && h.contains("php"))
    }

    // ------------------------------------------- 地址发布页：多级跳转（仅 chainPublisher 站点使用）

    private data class PublisherHop(val url: String, val html: String)

    /** scheme://host[:port]，保留端口（个别站点的论坛跑在非标准端口上） */
    private fun rootOf(url: String): String? = runCatching {
        val u = java.net.URL(url)
        val port = if (u.port > 0 && u.port != u.defaultPort) ":${u.port}" else ""
        "${u.protocol}://${u.host}$port"
    }.getOrNull()

    /**
     * 发布页常见的跳转方式：<meta http-equiv=refresh content="0.1;url=...">、location.href、location.replace。
     * 这类跳转壳经常返回 403/404，正文仍要读，所以走 allowErrorBody。
     */
    private fun nextHop(html: String, baseUrl: String): String? {
        val meta = Regex(
            "<meta[^>]+http-equiv\\s*=\\s*[\"']?refresh[\"']?[^>]*content\\s*=\\s*[\"'][^\"']*url\\s*=\\s*([^\"'>\\s]+)",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)
        val js = Regex(
            "(?:window\\.)?location(?:\\.href)?\\s*=\\s*[\"']([^\"']+)[\"']",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)
            ?: Regex("location\\.replace\\(\\s*[\"']([^\"']+)[\"']\\s*\\)", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)
        val raw = (meta ?: js)?.trim()?.replace("&amp;", "&") ?: return null
        if (raw.startsWith("javascript:", true)) return null
        return runCatching { java.net.URL(java.net.URL(baseUrl), raw).toString() }.getOrNull()
    }

    /** 跟随发布页跳转链，最多 6 跳；顺序为「起页 → … → 落地页」 */
    private fun followPublisherChain(start0: String): List<PublisherHop> {
        val hops = mutableListOf<PublisherHop>()
        val visited = HashSet<String>()
        // 老版本把发布页存成了没有 scheme 的 www.xxx.com，这里统一补齐，
        // 否则 OkHttp 直接抛 IllegalArgumentException，地址刷新每次都失败
        var cur: String? = start0.trim().let {
            if (it.startsWith("http", true)) it else "https://" + it.removePrefix("//")
        }
        while (cur != null && hops.size < 6 && visited.add(cur)) {
            var html = http.get(cur, allowErrorBody = true)
            if (html == null && cur.startsWith("https://", true)) {
                // 有些发布页只开了 http
                val plain = "http://" + cur.removePrefix("https://")
                if (visited.add(plain)) html = http.get(plain, allowErrorBody = true)
            }
            if (html == null) break
            hops.add(PublisherHop(cur, html))
            cur = nextHop(html, cur)
        }
        return hops
    }

    /**
     * 多级跳转式发布页（搜书吧：永久地址 → 中转域名 → 落地页「最新地址」）。
     * 与通用 [resolveAddress] 完全独立，只有 SiteProfile.chainPublisher 的站点会走这里。
     */
    suspend fun resolveAddressByChain(pageUrl: String): String? = withContext(Dispatchers.IO) {
        val hops = followPublisherChain(pageUrl)
        if (hops.isEmpty()) return@withContext null

        // 某一跳已经落到论坛上，直接用
        hops.firstOrNull { looksLikeForum(it.html) }?.let { return@withContext rootOf(it.url) }

        // 从最后一跳往前找：「最新地址 / 进入 / 访问」按钮通常在落地页上。
        // 倒序是为了防止落地页后面还挂着广告跳转，导致最后一跳不是真正的落地页。
        val chainUrls = hops.map { it.url }
        for (hop in hops.asReversed()) {
            pickAddressLink(hop.html, hop.url, chainUrls)?.let { return@withContext it }
        }

        // 兜底：仍交给通用解析器（它认得 JS 配置项、base64 等写法）
        resolveAddress(hops.last().url)
    }

    /** 从落地页里挑「最新地址」类链接并逐个探测，返回可用的站点根地址 */
    private fun pickAddressLink(html: String, baseUrl: String, chainUrls: List<String>): String? {
        val chainHosts = chainUrls.mapNotNull { runCatching { java.net.URL(it).host }.getOrNull() }
            .map { it.lowercase() }
            .toSet()
        val doc = Jsoup.parse(html, baseUrl)
        val candidates = doc.select("a[href]").mapNotNull { a ->
            val raw = a.attr("href").trim()
            if (raw.isEmpty() || raw.startsWith("javascript", true) || raw.startsWith("#") ||
                raw.startsWith("mailto", true) || raw.startsWith("tel", true)
            ) return@mapNotNull null
            val hint = (a.text() + " " + a.attr("id") + " " + a.attr("class")).lowercase()
            val preferred = hint.contains("最新") || hint.contains("进入") || hint.contains("访问") ||
                hint.contains("地址") || hint.contains("enter") || hint.contains("domain")
            val abs = runCatching { a.absUrl("href") }.getOrDefault("")
            if (!abs.startsWith("http")) return@mapNotNull null
            val host = runCatching { java.net.URL(abs).host }.getOrNull()?.lowercase().orEmpty()
            if (host.isBlank() || host in chainHosts || isNoiseHost(host)) return@mapNotNull null
            (if (preferred) 0 else 1) to abs
        }.sortedBy { it.first }.map { it.second }

        for (url in candidates.distinct().take(8)) {
            val root = rootOf(url) ?: continue
            if (looksLikeForum(http.get("$root/", allowErrorBody = true).orEmpty())) return root
            if (root.startsWith("https://")) {
                val plain = "http://" + root.removePrefix("https://")
                if (looksLikeForum(http.get("$plain/", allowErrorBody = true).orEmpty())) return plain
            }
        }
        return null
    }

    // ------------------------------------------------- 首页门户四格（最新主题/最新回复/热帖/精华）

    /** 首页三个分栏一次抓下来的结果（view 顺序：newthread / newreply / hot） */
    data class PortalBundle(
        val newthread: List<ThreadSummary> = emptyList(),
        val newreply: List<ThreadSummary> = emptyList(),
        val hot: List<ThreadSummary> = emptyList(),
        val digest: List<ThreadSummary> = emptyList()
    ) {
        fun of(view: String): List<ThreadSummary> = when (view) {
            "newreply" -> newreply
            "hot" -> hot
            "digest" -> digest
            else -> newthread
        }

        fun isEmpty(): Boolean =
            newthread.isEmpty() && newreply.isEmpty() && hot.isEmpty() && digest.isEmpty()
    }

    /**
     * 一次请求把「最新主题 / 最新回复 / 热帖 / 精华」全部解析出来。
     *
     * 这四个列表本来就在同一个首页/四格的页面里，之前按分栏各请求一次，
     * 等于把同一个几百 KB 的页面下了三遍——既慢又容易被站点限流。
     *
     * 尚香、搜书吧的首页都是标准 Discuz 四格（最新主题 / 最新回复 / 热帖 / 精华），
     * 一律只解析这一份，四个分栏的内容和站点首页完全一致：
     * 尚香四格带 tip（作者 / 时间 / 查看 / 回复），搜书吧只有标题（完整书名在 title 属性）。
     */
    suspend fun portalBundle(site: Site): PortalBundle = withContext(Dispatchers.IO) {
        val base = site.baseUrl.trimEnd('/')

        fun bundleFrom(html: String): PortalBundle {
            val pc = PortalBundle(
                newthread = parsePortal(html, site, "newthread"),
                newreply = parsePortal(html, site, "newreply"),
                hot = parsePortal(html, site, "hot"),
                digest = parsePortal(html, site, "digest")
            )
            if (!pc.isEmpty()) return pc
            return PortalBundle(
                newthread = parsePortalMobile(html, site, "newthread"),
                newreply = parsePortalMobile(html, site, "newreply"),
                hot = parsePortalMobile(html, site, "hot"),
                digest = parsePortalMobile(html, site, "digest")
            )
        }

        // 1) 优先「电脑版」：首页四格带 tip 属性，作者/查看/回复齐全
        val pcHtml = http.get("$base/forum.php?mobile=no", desktop = true)
        if (pcHtml != null && !isServerErrorPage(pcHtml)) {
            if (isLoginLanding(pcHtml)) {
                lastListIssue = "登录态已失效（站点跳到了登录页）。去「论坛」页用网页模式重新登录一次即可"
                return@withContext PortalBundle()
            }
            val b = bundleFrom(pcHtml)
            if (!b.isEmpty()) {
                AppLog.d(
                    "四格",
                    "${site.name} 最新=${b.newthread.size} 回复=${b.newreply.size} " +
                        "热帖=${b.hot.size} 精华=${b.digest.size}"
                )
                return@withContext b
            }
        }

        // 2) 手机版首页：.tabs + #tabs-box 里的四页列表
        val home = http.get("$base/")
        if (home != null && !isServerErrorPage(home)) {
            if (isLoginLanding(home)) {
                lastListIssue = "登录态已失效（站点跳到了登录页）。去「论坛」页用网页模式重新登录一次即可"
                return@withContext PortalBundle()
            }
            val b = bundleFrom(home)
            if (!b.isEmpty()) return@withContext b
        }
        PortalBundle()
    }

    suspend fun portal(site: Site, view: String): List<ThreadSummary> =
        portalBundle(site).of(view)

    /** 站点自身报错页（Discuz 数据库错误 / SQL 错误 / 空壳），当作抓取失败处理 */
    private fun isServerErrorPage(html: String): Boolean {
        if (html.length < 300) return true
        return html.contains("Discuz! Database Error") ||
            html.contains("SQL syntax") ||
            html.contains("Database Error") ||
            html.contains("数据库错误") ||
            html.contains("Query Error")
    }

    private data class TipMeta(
        val title: String = "",
        val author: String = "",
        val time: String = "",
        val views: Int = 0,
        val replies: Int = 0
    )

    /**
     * 解析 Discuz 首页四格链接上的 tip 属性。
     * 形如：标题: <strong>书名 作者：某某[已完结]</strong><br/>作者: 张三 (13 小时前)<br/>查看/回复: 2201/6
     * 先按 <br> 分段，再逐段匹配，避免标题内的「作者：」干扰。
     */
    private fun parseTip(rawTip: String): TipMeta {
        if (rawTip.isBlank()) return TipMeta()
        val segs = rawTip.split(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE))
            .map { Jsoup.parse(it).text().trim() }
            .filter { it.isNotEmpty() }

        var title = ""
        var author = ""
        var time = ""
        var views = 0
        var replies = 0

        for (seg in segs) {
            when {
                seg.startsWith("标题") -> {
                    title = seg.replaceFirst(Regex("^标题[:：]\\s*"), "").trim()
                }
                seg.startsWith("作者") -> {
                    val rest = seg.replaceFirst(Regex("^作者[:：]\\s*"), "").trim()
                    val m = Regex("^(.*?)\\s*[（(]\\s*([^）)]*?)\\s*[）)]\\s*$").find(rest)
                    if (m != null) {
                        author = m.groupValues[1].trim()
                        time = m.groupValues[2].trim()
                    } else author = rest
                }
                seg.contains("查看") && seg.contains("回复") -> {
                    val m = Regex("(\\d+)\\s*/\\s*(\\d+)").find(seg)
                    if (m != null) {
                        views = m.groupValues[1].toIntOrNull() ?: 0
                        replies = m.groupValues[2].toIntOrNull() ?: 0
                    }
                }
            }
        }
        return TipMeta(title, author, time, views, replies)
    }

    /**
     * 手机模板首页的「四页 tab」：最新主题 / 最新回复 / 论坛热帖 / 精华帖子。
     * 结构：div.tabs a（页签）+ #tabs-box .swiper-slide（同序四页 ul>li>a）。
     * 条目形如 <a><span class="mybk">半小时前</span><span class="mico"></span>标题</a>，
     * 热帖页的前缀是「2449 人气」并带 <span class="mnum">排名</span>。
     */
    private fun parsePortalMobile(html: String, site: Site, view: String): List<ThreadSummary> {
        val doc = Jsoup.parse(html, site.baseUrl)
        val tabs = doc.select("div.tabs a")
        if (tabs.isEmpty()) return emptyList()

        val want = when (view) {
            "newreply" -> "最新回复"
            "hot" -> "热帖"
            "digest" -> "精华"
            else -> "最新主题"
        }
        var index = -1
        tabs.forEachIndexed { i, a -> if (index < 0 && a.text().trim().contains(want)) index = i }
        if (index < 0) return emptyList()

        val slide = doc.select("#tabs-box .swiper-slide").getOrNull(index) ?: return emptyList()
        val out = LinkedHashMap<String, ThreadSummary>()
        val profile = site.profile
        for (a in slide.select("li a[href]")) {
            val href = a.attr("href")
            val tid = tidOf(href) ?: continue
            if (out.containsKey(tid)) continue

            val badge = a.selectFirst("span.mybk")?.text()?.trim().orEmpty()
            val clone = a.clone()
            clone.select("span.mybk, span.mico, span.mnum").remove()
            val title = clone.text().trim()
            if (title.length < 2) continue

            val replies = Regex("(\\d+)\\s*回复").find(badge)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val views = Regex("(\\d+)\\s*人气").find(badge)?.groupValues?.get(1)?.toIntOrNull() ?: 0

            // badge 有三种：时间（最新主题）、N 回复（最新回复）、N 人气（热帖）；
            // 精华页的 badge 是作者名，需要放回 author 字段而不是当成时间。
            val timeLike = Regex(
                "(\\d+\\s*(?:秒|分钟|小时|天)前|半小时前|刚刚|昨天|前天|\\d{4}-\\d{1,2}-\\d{1,2}|\\d{1,2}-\\d{1,2})"
            ).containsMatchIn(badge)
            val timeRaw = if (timeLike) badge else ""
            val badgeAuthor = if (timeLike || replies > 0 || views > 0) "" else badge

            out[tid] = ThreadSummary(
                siteId = site.id,
                tid = tid,
                fid = Regex("fid=(\\d+)").find(href)?.groupValues?.get(1) ?: "",
                title = title,
                author = badgeAuthor,
                replies = replies,
                views = views,
                lastTime = timeRaw,
                ts = TimeUtil.parse(timeRaw),
                url = if (profile.normalizeRedirect) threadUrl(a, site, tid, href)
                else runCatching { a.absUrl("href") }.getOrDefault("").ifBlank { href }
            )
        }
        return out.values.toList()
    }

    fun parsePortal(html: String, site: Site, view: String): List<ThreadSummary> {
        val doc = Jsoup.parse(html, site.baseUrl)
        val boxClass = when (view) {
            "newreply" -> "replaybox"
            "hot" -> "hottiebox"
            "digest" -> "goodtiebox"
            else -> "subjectbox"
        }
        // 桌面端四格：div.subjectbox / replaybox / hottiebox / goodtiebox
        // 个别模板换了 class，就按列名（h4 文案）精确定位，避免四栏全落到同一个块上
        val boxTitle = when (view) {
            "newreply" -> "最新回复"
            "hot" -> "热帖"
            "digest" -> "精华"
            else -> "最新主题"
        }
        val box = doc.selectFirst(".$boxClass")
            ?: doc.select("h3, h4").firstOrNull { it.text().contains(boxTitle) }?.parent()
            ?: return emptyList()

        val out = LinkedHashMap<String, ThreadSummary>()
        val profile = site.profile
        for (a in box.select("ul.category_newlist li a, li a")) {
            val href = a.attr("href")
            val tid = tidOf(href) ?: continue
            if (out.containsKey(tid)) continue

            // tip 属性: 标题: <strong>xx</strong><br/>作者: 某某 (13 小时前)<br/>查看/回复: 2201/6
            // 注意：标题里本身常含「作者：」（书名+作者），必须按 <br> 分段解析，
            // 否则正则会把标题截断到正文里的「作者：」处，作者也会取错。
            val meta = parseTip(a.attr("tip"))
            val title = meta.title.ifBlank {
                if (profile.titleFromAttr) fullTitle(a)
                else a.text().trim().ifBlank { a.attr("title").trim() }
            }
            if (title.length < 2) continue

            val author = meta.author
            val timeRaw = meta.time
            val views = meta.views
            val replies = meta.replies
            // 四格的 li 里如果带 #标签 也一并取出来
            val tags = a.closest("li")?.select("a.tag-required-tag")
                ?.map { it.text().trim().removePrefix("#").trim() }
                ?.filter { it.isNotBlank() && it.length <= 12 }
                ?.distinct()
                ?.take(4)
                .orEmpty()

            out[tid] = ThreadSummary(
                siteId = site.id,
                tid = tid,
                fid = Regex("fid=(\\d+)").find(href)?.groupValues?.get(1) ?: "",
                title = title,
                author = author,
                replies = replies,
                views = views,
                lastTime = timeRaw,
                ts = TimeUtil.parse(timeRaw),
                url = if (profile.normalizeRedirect) threadUrl(a, site, tid, href)
                else runCatching { a.absUrl("href") }.getOrDefault("").ifBlank { href },
                tags = tags
            )
        }
        return out.values.toList()
    }

    suspend fun threads(
        site: Site,
        fid: String,
        page: Int,
        orderBy: String = "lastpost"
    ): List<ThreadSummary> = threadPage(site, fid, page, orderQuery = boardOrderQuery(orderBy)).threads

    /**
     * 版块列表（含页面自带的分类筛选）。
     *
     * Discuz 的 forumdisplay 有两组参数：
     *  1) 排序：filter=lastpost/dateline/heat/digest + orderby=...
     *  2) 分类：filter=typeid&typeid=N
     * 两组都带 filter，Discuz 只认最后一个，所以分类参数放后面。
     *
     * 参数直接由 [BoardFilter.query] 传进来（就是页面链接里的原文），
     * 不再做 orderby→filter 的猜测映射——猜错时界面点了没反应，看起来像「不刷新」。
     */
    suspend fun threadPage(
        site: Site,
        fid: String,
        page: Int,
        orderQuery: String = "",
        typeQuery: String = ""
    ): ThreadPage = withContext(Dispatchers.IO) {
        val query = mergeBoardQuery(orderQuery, typeQuery)
        // 必须显式要电脑版：站点默认可能回手机模板，手机模板没有 a.xst / tbody#normalthread 这套结构
        val path = "forum.php?mod=forumdisplay&fid=$fid&mobile=no" +
            if (query.isEmpty()) "" else "&$query"
        val url = pageUrl(site, path, page)
        lastListIssue = null
        AppLog.d("板块", "请求 fid=$fid 第$page 页 ${query.ifBlank { "全部" }}")
        val html = http.get(url, referer = u(site, "forum.php"))
        if (html == null) {
            lastListIssue = "请求失败：网络不通或站点无响应"
            return@withContext ThreadPage()
        }
        if (isServerErrorPage(html)) {
            lastListIssue = "站点返回错误页（服务器过载或数据库异常）"
            return@withContext ThreadPage()
        }
        if (isLoginLanding(html)) {
            lastListIssue = "登录态已失效（站点跳到了登录页）。去「论坛」页用网页模式重新登录一次即可"
            return@withContext ThreadPage()
        }
        val doc = Jsoup.parse(html, site.baseUrl)
        val threads = parseThreads(html, site)
        val topCount = threads.count { it.isTop }
        val queryLabel = if (query.isEmpty()) "默认" else query
        AppLog.d("板块", "fid=$fid 第$page 页 帖子=${threads.size} 置顶=$topCount 参数=$queryLabel")
        if (threads.isEmpty()) {
            lastListIssue = "页面里没有解析到帖子（可能是空版块、权限不足，或模板结构变了）"
        }
        ThreadPage(
            threads = threads,
            orders = if (page <= 1) parseOrders(doc) else emptyList(),
            types = if (page <= 1) parseTypes(doc) else emptyList()
        )
    }

    /**
     * 拼「排序 + 分类」的查询串。
     *
     * Discuz 的 `filter` 只能有一个值：`filter=typeid` 和 `filter=lastpost` 同时出现时，
     * 服务端只认最后一个，排序就失效了。所以选了分类时，分类的 filter 优先，
     * 排序只保留 `orderby=`（Discuz 允许 `filter=typeid&typeid=3&orderby=lastpost` 这样组合）。
     */
    private fun mergeBoardQuery(orderQuery: String, typeQuery: String): String {
        val order = orderQuery.trim()
        val type = typeQuery.trim()
        if (type.isEmpty()) return order
        if (order.isEmpty()) return type
        // 分类优先；排序只保留 orderby（filter 已被分类占用）
        val orderType = Regex("typeid=(\\d+)").find(type)?.groupValues?.get(1)
        val keep = order.split("&").filter { it.startsWith("orderby=") }
        val base = "filter=typeid" + if (orderType != null) "&typeid=$orderType" else ""
        return (listOf(base) + keep).filter { it.isNotBlank() }.joinToString("&")
    }

    /**
     * 解析第一层排序。
     *
     * 直接拿页面链接里的 query 原文当参数，绝不自己翻译 orderby：
     * 尚香的实际入口是
     *   最新 `filter=lastpost&orderby=lastpost`
     *   热门 `filter=heat&orderby=heats`
     *   热帖 `filter=hot`
     *   精华 `filter=digest&digest=1`
     * 以前按 orderby 反推 filter（dateline→author 之类），服务端不认，点了跟没点一样。
     * 带 `dateline=` 的是「全部时间/一天/一周」时间范围，带 `specialtype=` 的是悬赏，都不是排序。
     */
    private fun parseOrders(doc: Document): List<BoardFilter> {
        val scopes = listOfNotNull(
            doc.selectFirst("#threadlist div.tf"),
            doc.selectFirst("div.tf"),
            doc.selectFirst("div.dhnav_box")
        )
        val found = LinkedHashMap<String, BoardFilter>()
        val skip = setOf("新窗", "更多", "全部主题", "显示置顶", "全部悬赏", "全部时间")
        for (scope in scopes) {
            for (a in scope.select("a[href]")) {
                val href = a.attr("href")
                if (!href.contains("filter=")) continue
                if (href.contains("dateline=") || href.contains("specialtype=")) continue
                val name = a.text().trim()
                if (name.isBlank() || name.length > 6 || name in skip) continue
                val query = href.substringAfter("?", "")
                    .split("&")
                    .filter {
                        it.startsWith("filter=") || it.startsWith("orderby=") || it.startsWith("digest=")
                    }
                    .joinToString("&")
                if (query.isBlank()) continue
                if (found.values.any { it.query == query }) continue
                found[query] = BoardFilter(query, name, query)
            }
            if (found.isNotEmpty()) break
        }
        return if (found.isEmpty()) DefaultBoardOrders
        else listOf(BoardFilter("", "全部", "")) + found.values.toList()
    }

    /**
     * 解析分类筛选栏（第二层）。
     *
     * 电脑版在 `ul#thread_types`（`li > a`，名字后面跟着主题数），手机版在 `.dhnavs_box`。
     * 限定不到范围就全页扫描，再靠命名规则把帖子卡片上的「#标签」排掉。
     */
    private fun parseTypes(doc: Document): List<BoardFilter> {
        fun collect(root: Element): List<BoardFilter> = root.select("a[href*=typeid]").mapNotNull { a ->
            val key = Regex("typeid=(\\d+)").find(a.attr("href"))?.groupValues?.get(1)
                ?: return@mapNotNull null
            // 「纯爱<span class="xg1 num">20233</span>」：ownText 只取链接自身的文字，天然不含主题数
            var name = a.ownText().replace('\u00A0', ' ').trim()
            if (name.isBlank()) {
                name = a.text()
                    .replace('\u00A0', ' ')
                    .replace(Regex("\\s*\\d+\\s*$"), "")
                    .trim()
            }
            // ownText 也可能是「NTR被绿 35327」（数字是纯文本节点），再剥一次
            name = name.replace(Regex("\\s*\\d+\\s*$"), "").trim()
            if (name.isBlank() || name.startsWith("#") || name == "全部分类" || name.length > 12) null
            else BoardFilter(key, name, "filter=typeid&typeid=$key")
        }

        val scoped = listOfNotNull(
            doc.selectFirst("ul#thread_types"),
            doc.selectFirst("div.dhnavs_box"),
            doc.selectFirst("#threadlist")?.parent()
        ).firstNotNullOfOrNull { root -> collect(root).takeIf { it.isNotEmpty() } }
        val list = scoped ?: collect(doc)
        return list
            .groupBy { it.key }
            .map { (_, group) -> group.minByOrNull { it.name.length } ?: group.first() }
    }


    suspend fun search(site: Site, keyword: String): List<ThreadSummary> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(keyword, "UTF-8")
        val html = http.get(u(site, "search.php?mod=forum&srchtxt=$q&searchsubmit=yes"))
            ?: return@withContext emptyList()
        parseThreads(html, site)
    }

    fun parseThreads(html: String, site: Site): List<ThreadSummary> {
        val doc = Jsoup.parse(html, site.baseUrl)
        val map = LinkedHashMap<String, ThreadSummary>()
        // 页面里只要有真正的帖子列表容器，就只认容器里的链接：
        // 顶部快捷导航（「永久地址」「每日签到」）、广告位、页脚里的帖子链接一律不当内容。
        val listBox = doc.selectFirst(
            "#threadlist, tbody[id^=normalthread], tbody[id^=stickthread], ul.threadlist, .threadlist, table.tl"
        )
        val all = doc.select("a[href*=viewthread], a[href*=thread-]")
            .toList()
            .filter { a ->
                if (a.closest(".ma-text-grid, .mobile-adv, .tabs, #tabs-box, #nv, #hd, #ft, #pt, .pg, #um, .nv") != null) {
                    false
                } else if (listBox != null) {
                    a.closest(
                        "#threadlist, tbody[id^=normalthread], tbody[id^=stickthread], ul.threadlist, .threadlist, table.tl"
                    ) != null
                } else true
            }
        // 电脑版列表的标题链接是 a.xst；其余（“新窗口打开”“查看”“回复”等操作入口）不参与
        val preferred = all.filter { it.classNames().contains("xst") }
        val links = if (preferred.isNotEmpty()) preferred else all
        val noiseTitles = setOf(
            "新窗口打开", "查看", "回复", "收藏", "分享", "更多", "返回", "上一页", "下一页",
            "永久地址", "每日签到", "积分充值", "排行榜", "登录", "注册", "找回密码"
        )
        // 只有 profile 声明了 titleFromAttr 的站点（搜书吧）才用 title 属性补全被截断的标题
        val profile = site.profile

        for (a in links) {
            val href = a.attr("href")
            val tid = tidOf(href) ?: continue
            var title = if (profile.titleFromAttr) fullTitle(a) else a.text().trim()
            if (title.length < 3) title = a.attr("title").trim()
            if (title.length < 3) continue
            if (title in noiseTitles || title.contains("新窗口打开")) continue
            if (title.endsWith("...") && title.length <= 3) continue
            if (map.containsKey(tid)) continue

            // 兜底：标题被 JS 渲染成空文本时，从同一行里找带文字的同帖链接
            if (title.length < 6) {
                val row = a.closest("tbody, tr, li") ?: a.parent()
                val rowLinks = row?.select("a[href*=viewthread], a[href*=thread-]")?.toList().orEmpty()
                val alt = rowLinks
                    .firstOrNull { it.text().trim().length >= 6 && !it.text().contains("新窗口打开") }
                    ?.text()?.trim()
                if (!alt.isNullOrBlank()) title = alt
            }

            val box: Element = a.closest("li, tr, .thread, .list, .bm_c, dl") ?: a.parent() ?: a
            val text = box.text()
            val replies = Regex("(\\d+)\\s*(?:个)?回复").find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("(\\d+)\\s*/\\s*\\d+").find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: box.selectFirst("td.num a, .num a, .replies a")?.text()?.toIntOrNull()
                ?: 0
            val views = Regex("(\\d+)\\s*(?:次)?(?:查看|浏览|阅读)").find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("\\d+\\s*/\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: box.selectFirst("td.num em, .num em, .views")?.text()?.toIntOrNull()
                ?: 0

            val author = box.selectFirst("cite a, .by cite a, .author a, .favatar a, a.xw1")?.text()?.trim()
                ?: ""
            val lastTimeRaw = Regex(
                "\\d{4}-\\d{1,2}-\\d{1,2}([\\s\\u00A0]+\\d{1,2}:\\d{1,2})?|\\d{1,2}-\\d{1,2}[\\s\\u00A0]+\\d{1,2}:\\d{1,2}|\\d+[\\s\\u00A0]*(?:秒|分钟|小时|天)前|昨天[\\s\\u00A0]*\\d{1,2}:\\d{1,2}|昨天"
            ).find(text)?.value ?: ""
            val ts = TimeUtil.parse(lastTimeRaw)
            val fid = Regex("fid=(\\d+)").find(href)?.groupValues?.get(1) ?: ""
            // 置顶识别：电脑版置顶帖在 tbody#stickthread_xxx 里（行本身没 class），
            // 手机版是 li.list_top，另外还有图标 / 「置顶」标记等兜底特征
            val isTop = a.closest("tbody[id^=stickthread], li.list_top, tr.list_top, .list_top") != null ||
                box.classNames().any { it.contains("top", true) } ||
                title.startsWith("置顶") ||
                box.selectFirst("i.tpin, .tpin") != null ||
                box.selectFirst("em")?.text()?.contains("置顶") == true
            val isDigest = box.selectFirst("img[src*=digest], em")?.text()?.contains("精华") == true
            val isNew = box.classNames().any { it.contains("new", true) }
            // 标题前的 [纯爱] 是版块分类
            val typeName = box.selectFirst("th em a[href*=typeid], em a[href*=typeid]")
                ?.text()?.trim()
                ?.takeIf { it.isNotBlank() && it.length <= 12 && !it.contains("置顶") }
                .orEmpty()
            // 标题后的 #ntl #母女花 是标签，卡片上照原样带出来
            val tags = box.select("a.tag-required-tag")
                .map { it.text().trim().removePrefix("#").trim() }
                .filter { it.isNotBlank() && it.length <= 12 }
                .distinct()
                .take(4)

            map[tid] = ThreadSummary(
                siteId = site.id,
                tid = tid,
                fid = fid,
                title = title,
                author = author,
                replies = replies,
                views = views,
                lastTime = lastTimeRaw,
                lastReplier = "",
                boardName = "",
                isTop = isTop,
                isDigest = isDigest,
                isNew = isNew,
                ts = ts,
                url = if (profile.normalizeRedirect) threadUrl(a, site, tid, href)
                else runCatching { a.absUrl("href") }.getOrDefault("").ifBlank { href },
                typeName = typeName,
                tags = tags
            )
        }
        return map.values.toList()
    }

    // ---------------------------------------------------------------- 详情

    /** 最近一次详情抓取的诊断信息，失败时展示给用户/日志排查 */
    var lastDetailDebug: String = ""
        private set

    suspend fun detail(site: Site, tid: String, page: Int = 1, rawUrl: String? = null): ThreadDetail? =
        withContext(Dispatchers.IO) {
            // 依次尝试：列表里的原始链接（可能带 _dsign 防采集签名）→ 手机版 → 桌面版 → Archiver。
            // 部分站点（如尚香书苑）手机模板无 post_ 容器，且链接带签名，自己拼 URL 会拿不到正文。
            val candidates = buildList {
                val raw = rawUrl?.trim().orEmpty()
                // 声明了 normalizeRedirect 的站点：「最新回复」给的是 redirect&goto=lastpost，
                // 直接请求会落到末页，不能当详情入口；其他站点照旧优先用原始链接
                val skipRaw = site.profile.normalizeRedirect &&
                    (raw.contains("mod=redirect", true) || raw.contains("goto=lastpost", true))
                if (raw.isNotEmpty() && !skipRaw && page <= 1) {
                    add(if (raw.startsWith("http")) raw else u(site, raw))
                }
                add(pageUrl(site, "forum.php?mod=viewthread&tid=$tid", page))
                // 实证：同一 tid 的裸链接只回 2~3 KB 提示页时，带 page 参数能拿到整页
                // （日志里 441345 的 &page=2 是 79 KB，同一时刻的裸链接是 2.7 KB）
                add(u(site, "forum.php?mod=viewthread&tid=$tid&page=" + page.coerceAtLeast(1)))
                // 伪静态地址：页面 <link rel="canonical"> 用的就是它，部分防采集规则只对
                // forum.php?mod=viewthread 形态下手，伪静态反而能拿到整页
                add(u(site, "thread-$tid-" + page.coerceAtLeast(1) + "-1.html"))
                add(u(site, "forum.php?mod=viewthread&tid=$tid" + if (page > 1) "&page=$page" else ""))
                // 显式要电脑版：手机模板有时只回一个空壳（正文靠前端 JS 拉），电脑版永远带 t_f
                add(
                    u(
                        site,
                        "forum.php?mod=viewthread&tid=$tid" +
                            (if (page > 1) "&page=$page" else "") + "&mobile=no"
                    )
                )
                add(u(site, "archiver/tid-$tid.html" + if (page > 1) "?page=$page" else ""))
            }.distinct()

            // 并发场景下多个入口都会往里写，用同步列表避免互踩
            val notes = java.util.Collections.synchronizedList(mutableListOf<String>())
            // 尚香书苑：各入口互相独立，串行请求最坏要等 4×18 秒。
            // 并发探测，谁先拿到正文用谁，整体给 12 秒上限。
            if (site.profile.parallelDetail) {
                return@withContext detailRacing(site, tid, page, candidates, notes)
            }
            for (url in candidates) {
                val parsed = fetchDetail(url, site, tid, page, notes)
                if (parsed != null) {
                    lastDetailDebug = "成功：" + parsed.posts.size + " 楼"
                    AppLog.d("正文", "tid=$tid 第$page 页 成功 ${parsed.posts.size} 楼")
                    return@withContext parsed
                }
            }
            lastDetailDebug = notesSnapshot(notes).ifBlank { "无可用请求" }
            AppLog.w("正文", "tid=$tid 第$page 页 抓取失败：$lastDetailDebug")
            null
        }

    /** 用已渲染好的 HTML（例如 WebView 取回的 DOM）解析详情 */
    fun parseDetailHtml(site: Site, tid: String, page: Int, html: String): ThreadDetail? =
        runCatching { parseDetail(html, site, tid, page) }.getOrNull()

    /**
     * 详情入口探测（尚香书苑）。
     *
     * 先给「列表里的原始链接」2.5 秒——正常登录状态下它直接就能拿到正文，不会多打请求；
     * 超时了再把其余入口并发出去，谁先解析成功用谁。整体上限 12 秒，
     * 相比串行（最坏 4×18 秒）能明显缩短「点进正文」的等待。
     */
    private suspend fun detailRacing(
        site: Site,
        tid: String,
        page: Int,
        candidates: List<String>,
        notes: MutableList<String>
    ): ThreadDetail? {
        // 用独立 scope 发请求：OkHttp 的阻塞式调用无法被协程中断，
        // 若挂在调用方的结构化作用域里，最先返回后还要等其余请求跑完，等于白并发。
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // null 表示「全部入口都试完了但没拿到」，用来提前结束，而不是干等到超时
        val first = CompletableDeferred<ThreadDetail?>()
        val left = java.util.concurrent.atomic.AtomicInteger(candidates.size)

        fun fire(url: String) {
            scope.launch {
                val parsed = runCatching { fetchDetail(url, site, tid, page, notes) }.getOrNull()
                if (parsed != null) {
                    first.complete(parsed)
                } else if (left.decrementAndGet() == 0) {
                    first.complete(null)
                }
            }
        }

        val hit = try {
            // 错峰发请求：3 个入口同时打，站点容易统一回 2~3KB 提示页。
            // 但间隔不能太长——首个入口失败的话，等 0.9 秒才发第二个，用户已经在盯着转圈了。
            // 现在 350ms 补一个，命中即停；WebView 那边 500ms 就开始并行，两条路谁快用谁。
            candidates.forEachIndexed { index, url ->
                if (index == 0) {
                    fire(url)
                } else {
                    scope.launch {
                        delay(350L * index)
                        if (!first.isCompleted) fire(url)
                    }
                }
            }
            withTimeoutOrNull(5_000L) { first.await() }
        } finally {
            scope.cancel()
        }

        if (hit != null) {
            lastDetailDebug = "成功：${hit.posts.size} 楼（并发探测）"
            AppLog.d("正文", "tid=$tid 并发探测成功 ${hit.posts.size} 楼")
            return hit
        }

        // 第二轮：站点偶尔会先回一个提示页（防盗链/频率限制/CDN 把错误页缓存到了这个 URL），
        // 换一条 URL 再要一次，多数能拿到正常页；仍然失败才算真失败。
        // 等 400ms 而不是 1 秒——站内渲染已经在并行跑了，这里只是补一刀。
        delay(400L)
        for (url in retryCandidates(site, tid, page)) {
            val parsed = runCatching { fetchDetail(url, site, tid, page, notes) }.getOrNull()
            if (parsed != null) {
                lastDetailDebug = "成功：${parsed.posts.size} 楼（重试）"
                AppLog.d("正文", "tid=$tid 重试成功 ${parsed.posts.size} 楼")
                return parsed
            }
        }

        lastDetailDebug = notesSnapshot(notes)
            .ifBlank { "并发探测 ${candidates.size} 个入口均未拿到正文" }
        AppLog.w("正文", "tid=$tid 并发探测失败：$lastDetailDebug")
        return null
    }

    /**
     * 诊断信息快照。并发请求可能还在往列表里写（超时后 OkHttp 的阻塞调用无法中断），
     * 所以先加锁拷一份再拼字符串，避免 ConcurrentModificationException。
     */
    private fun notesSnapshot(notes: MutableList<String>): String =
        synchronized(notes) { notes.toList() }.joinToString("\n")

    /** 重试用入口：加 _t 打散缓存键，避免再次命中同一个缓存下来的错误页 */
    private fun retryCandidates(site: Site, tid: String, page: Int): List<String> {
        val t = System.currentTimeMillis()
        val pageQuery = if (page > 1) "&page=$page" else ""
        return listOf(
            // 日志实证：裸链接回 2~3 KB 提示页时，带 page 参数的同一 tid 能拿到整页
            u(site, "forum.php?mod=viewthread&tid=$tid" + (pageQuery.ifBlank { "&page=1" }) + "&_t=$t"),
            u(site, "forum.php?mod=viewthread&tid=$tid$pageQuery&mobile=no&_t=$t"),
            u(site, "archiver/tid-$tid.html" + (if (page > 1) "?page=$page&_t=$t" else "?_t=$t"))
        )
    }

    private fun fetchDetail(
        url: String,
        site: Site,
        tid: String,
        page: Int,
        notes: MutableList<String>? = null
    ): ThreadDetail? {
        // 带 Referer，某些站点的防采集插件会校验来源
        val html = http.get(url, referer = site.baseUrl.trimEnd('/') + "/forum.php")
        if (html == null) {
            notes?.add("· 请求失败 " + url.substringAfter("://").take(60))
            return null
        }
        // 每个入口只解析一次整页。旧逻辑在登录检测、正文提取、失败诊断里各 parse 一次，
        // 多入口并发时这些重复 Jsoup 解析会抢满 CPU，反而拖慢已经拿到正文的入口。
        val doc = runCatching { Jsoup.parse(html, site.baseUrl) }.getOrNull()
        if (doc == null) {
            notes?.add("· HTML 解析失败 " + url.substringAfter("://").take(60))
            return null
        }
        val parsed = parseDetail(doc, html, site, tid, page)
        if (parsed != null && parsed.posts.isNotEmpty()) return parsed
        if (isLoginWall(doc, html)) {
            notes?.add("· 登录页 " + url.substringAfter("://").take(60))
            return null
        }
        val reason = stubReason(doc, html)
        notes?.add("· " + url.substringAfter("://").take(60) + " → " + reason)
        AppLog.d("正文", "tid=$tid 入口无正文：$reason")
        return null
    }

    /** 抓不到正文时，把「这个页面到底是什么」记下来（权限不足/审核中/请先登录都是小提示页） */
    private fun stubReason(doc: Document, html: String): String {
        val msg = doc.selectFirst("#messagetext, .alert_info, .alert_error, .alert_error_msg, div.message")
            ?.text()?.replace(Regex("\\s+"), " ")?.trim()?.take(70).orEmpty()
        val title = doc.title().trim().take(20)
        if (msg.isNotBlank()) return "提示页「$title」$msg"
        // 连提示语都没有时，把正文前几十个字一起记下来：下次能直接看出这 2~3 KB 到底是个什么页
        val snippet = doc.body()?.text()?.replace(Regex("\\s+"), " ")?.trim()?.take(60).orEmpty()
        return "无正文 ${html.length}B 标题「$title」" + if (snippet.isBlank()) "" else " 内容「$snippet」"
    }

    /**
     * 是否是「请先登录」页。
     * 基准：页面里有没有可解析的正文节点；只有完全没有正文、且明显是登录页时才判定为登录墙。
     * （早期版本用 post_/t_f 特征判断，误把使用 div.message 模板的站点当成登录页）
     */
    private fun isLoginWall(doc: Document, html: String): Boolean {
        if (html.contains("class=\"message\"") || html.contains("postmessage") ||
            html.contains("class=\"t_f\"") || html.contains("id=\"post_")
        ) return false
        if (doc.select(BODY_SELECTOR).any { it.text().isNotBlank() }) return false
        val title = doc.title()
        return title.contains("登录") || title.contains("提示") ||
            html.contains("member.php?mod=logging")
    }

    private fun parseDetail(html: String, site: Site, tid: String, page: Int): ThreadDetail? =
        parseDetail(Jsoup.parse(html, site.baseUrl), html, site, tid, page)

    private fun parseDetail(
        doc: Document,
        html: String,
        site: Site,
        tid: String,
        page: Int
    ): ThreadDetail? {
        // Discuz 的「提示信息」模板（权限不足 / 需购买 / 请先登录 / 审核中）里也有 div.message，
        // 长得很像正文；没有 post_ 容器时直接判负，别把提示语当成帖子内容渲染出来。
        if (doc.select("div[id^=post_]").isEmpty() &&
            doc.selectFirst("#messagetext, .alert_info, .alert_error, .alert_error_msg") != null
        ) return null
        val title = doc.selectFirst("#thread_subject")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst(".thread-title, .threadtitle, h1.ts, h1")?.text()?.trim()?.ifBlank { null }
            ?: doc.title().substringBefore(" - ").substringBefore("_").trim()
        val posts = parsePosts(doc, site)
        if (posts.isEmpty()) return null

        val totalPages = Regex("page=(\\d+)").findAll(html).mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull() ?: 1
        val fid = Regex("fid=(\\d+)").find(html)?.groupValues?.get(1)
            ?: Regex("fid-(\\d+)-").find(html)?.groupValues?.get(1) ?: ""
        val summary = ThreadSummary(
            siteId = site.id,
            tid = tid,
            fid = fid,
            title = title.ifBlank { posts.first().author + " 的帖子" },
            author = posts.first().author,
            replies = Regex("(\\d+)\\s*(?:个)?回复").find(doc.text())?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            views = 0,
            lastTime = posts.first().time,
            ts = TimeUtil.parse(posts.first().time)
        )
        return ThreadDetail(summary = summary, posts = posts, page = page, totalPages = totalPages)
    }

    private fun parsePosts(doc: Document, site: Site): List<Post> {
        // 方案 A：标准/桌面模板 div[id=post_xxx]（含内嵌正文节点）
        val nodes = doc.select("div[id^=post_]").mapNotNull { holder ->
            holder.selectFirst(BODY_SELECTOR)?.let { holder to it }
        }
        if (nodes.isNotEmpty()) {
            return nodes.mapIndexed { index, pair ->
                buildPost(pair.first, pair.second, index, extracted = true, site = site)
            }
        }

        // 方案 B：直接抓正文节点，再反推所属楼层容器
        // 覆盖手机版 div.message、定制模板 .post-content / .article-content 等
        val bodies = doc.select(BODY_SELECTOR).toList().filter { it.text().isNotBlank() }
        val out = mutableListOf<Post>()
        val seen = mutableSetOf<String>()
        for (body in bodies) {
            val holder = body.closest("div[id^=post_]")
                ?: body.closest("div.plc, div[id^=pid], li.post, .post, .thread-post, .postlist > div")
                ?: body.parent()?.parent()
                ?: continue
            val key = holder.text().take(40)
            if (key.isBlank() || !seen.add(key)) continue
            out.add(buildPost(holder, body, out.size, extracted = false, site = site))
        }
        return out
    }

    private companion object {
        /** 正文节点选择器，覆盖 Discuz 桌面版 / 手机版 / 常见定制模板 */
        const val BODY_SELECTOR =
            "td.t_f, div.t_f, div.message, div.postmessage, [id^=postmessage_], " +
                ".article-content, .post-content, .thread-content, .post_message"
    }

    private fun buildPost(holder: Element, body: Element, index: Int, extracted: Boolean, site: Site): Post {
        val baseUrl = site.baseUrl
        val pid = holder.id().removePrefix("post_")
        val author = holder.selectFirst("a.xw1, .authi .z a, .mtit a, .authi a, .favatar a, .pi a, .username, .author a, .postinfo a")
            ?.text()?.trim().orEmpty()
        val avatar = holder.selectFirst("img.avatar, .favatar img, .avatar img")?.attr("src").orEmpty()
            .let { if (it.startsWith("//")) "https:$it" else it }
        val uid = Regex("uid=(\\d+)").find(
            holder.selectFirst("a[href*=space-uid]")?.attr("href") ?: ""
        )?.groupValues?.get(1).orEmpty()

        // 发帖时间优先取「发表于 …」（#authorposton），侧栏 .pi 里全是积分/勋章，容易抓错
        val infoText = holder.selectFirst("#authorposton$pid")?.text()?.trim().orEmpty()
            .ifBlank {
                holder.selectFirst(".mtime, .pi, .authi, .favatar, .postinfo, .post-date, .time")
                    ?.text().orEmpty()
            }
        val timeRaw = Regex(
            "\\d{4}-\\d{1,2}-\\d{1,2}([\\s\\u00A0]+\\d{1,2}:\\d{1,2})?|\\d{1,2}-\\d{1,2}[\\s\\u00A0]+\\d{1,2}:\\d{1,2}|\\d+[\\s\\u00A0]*(?:秒|分钟|小时|天)前|昨天[\\s\\u00A0]*\\d{1,2}:\\d{1,2}|昨天"
        ).find(infoText)?.value.orEmpty()

        val floorText = holder.selectFirst(".mtit .y, strong a em, .pi strong, #authorposton$pid")?.text().orEmpty()
        val floor = when {
            floorText.contains("楼主") -> 1
            floorText.contains("沙发") -> 2
            floorText.contains("板凳") -> 3
            floorText.contains("地板") -> 4
            else -> index + 1
        }

        val html = body.html()
        var isOp = floor == 1
        if (!isOp && index == 0) isOp = true

        // 整页已经由 Jsoup 解析过，直接遍历正文节点；不再把 body.html() 再解析一遍。
        val blocks = HtmlBlocks.parse(body, baseUrl).toMutableList()
        // 附件不在正文节点里，而在同楼层的 .pattl 里（付费附件链接是 attachpay）。
        // 尚香书苑的模板把附件放在 t_fsz 里、可能包一层 ignore_js_op，用增强解析；
        // 其他站点继续走原来的 dl.tattl 逻辑，行为完全不变。
        val profile = site.profile
        val attSelector = if (profile.richAttachment) "div.pattl, dl.tattl, ignore_js_op"
        else "div.pattl, dl.tattl"
        val attScope = if (holder.selectFirst(attSelector) != null) holder
        else holder.parents().take(5).firstOrNull { it.selectFirst(attSelector) != null } ?: holder

        val attachments = if (profile.richAttachment) {
            Attachments.rich(attScope, baseUrl)
        } else {
            Attachments.legacy(attScope, baseUrl)
        }
        // 附件统一排在正文之后，避免插进段落中间；正文里已经出现过的图片不重复添加
        val inlineImages = blocks.filterIsInstance<Block.Image>().map { it.url }.toSet()
        blocks.addAll(attachments.filter { b -> b !is Block.Image || b.url !in inlineImages })

        return Post(
            pid = pid,
            floor = floor,
            author = author.ifBlank { "匿名" },
            uid = uid,
            avatar = avatar,
            time = timeRaw,
            html = html,
            blocks = blocks,
            isOp = isOp
        )
    }

    // ---------------------------------------------------------------- 登录 / 发帖

    private fun formhashOf(html: String): String =
        Regex("formhash=([0-9a-zA-Z]{6,})").find(html)?.groupValues?.get(1)
            ?: Jsoup.parse(html).selectFirst("input[name=formhash]")?.attr("value").orEmpty()

    /** 登录验证码（Discuz seccode）：hash + 图片字节 */
    data class LoginCaptcha(val hash: String, val image: ByteArray)

    /**
     * 取一次登录验证码。返回 null 表示该站点登录不需要验证码（或取图失败）。
     * 注意：验证码与当前会话 Cookie 绑定，因此必须在同一 Http 实例下取图并提交。
     */
    suspend fun loginCaptcha(site: Site): LoginCaptcha? = withContext(Dispatchers.IO) {
        http.clearSessionCookies(site)
        val loginUrl = u(site, "member.php?mod=logging&action=login&mobile=2")
        val html = http.get(loginUrl) ?: return@withContext null
        val hash = Regex("name=[\"']seccodehash[\"'][^>]*value=[\"']([^\"']+)[\"']")
            .find(html)?.groupValues?.get(1)
            ?: Regex("idhash=([0-9A-Za-z]+)").find(html)?.groupValues?.get(1)
            ?: return@withContext null

        val imgUrl = u(site, "misc.php?mod=seccode&action=update&idhash=$hash&modid=member::logging&mobile=2&t=${System.currentTimeMillis()}")
        val bytes = http.getBytes(imgUrl, referer = loginUrl) ?: return@withContext null
        if (bytes.size < 64) null else LoginCaptcha(hash, bytes)
    }

    suspend fun login(
        site: Site,
        username: String,
        password: String,
        captchaHash: String? = null,
        captchaCode: String? = null
    ): Result<String> =
        withContext(Dispatchers.IO) {
            val loginUrl = u(site, "member.php?mod=logging&action=login&mobile=2")
            val html = http.get(loginUrl)
                ?: return@withContext Result.failure(Exception("无法打开站点登录页，请检查网络或网址"))
            val loginhash = Regex("loginform_([0-9a-zA-Z]+)").find(html)?.groupValues?.get(1).orEmpty()
            val formhash = formhashOf(html)
            if (formhash.isBlank()) return@withContext Result.failure(Exception("未找到 formhash，可能不是 Discuz 论坛"))

            val postUrl = if (loginhash.isNotBlank())
                u(site, "member.php?mod=logging&action=login&loginsubmit=yes&handlekey=login&loginhash=$loginhash&inajax=1&mobile=2")
            else u(site, "member.php?mod=logging&action=login&loginsubmit=yes&inajax=1&mobile=2")

            val form = mutableMapOf(
                "formhash" to formhash,
                "referer" to u(site, "forum.php"),
                "loginfield" to "username",
                "fastloginfield" to "username",
                "username" to username,
                "password" to password,
                "questionid" to "0",
                "answer" to "",
                "cookietime" to "2592000"
            )
            if (!captchaHash.isNullOrBlank() && !captchaCode.isNullOrBlank()) {
                form["seccodehash"] = captchaHash
                form["seccodeverify"] = captchaCode
                form["seccodemodid"] = "member::logging"
            }

            val body = http.post(postUrl, form, referer = loginUrl)
                ?: return@withContext Result.failure(Exception("登录请求失败，请检查网络"))

            val ok = body.contains("欢迎您回来") || body.contains("现在将转入登录前页面") ||
                body.contains("欢迎你回来") || http.hasLoginCookie(site)
            if (!ok) {
                val raw = Regex("errorhandle_[a-z]*\">([^<]+)<").find(body)?.groupValues?.get(1)
                    ?: Regex("<p>([^<]{4,60})</p>").find(body)?.groupValues?.get(1)
                    ?: ""
                val msg = Jsoup.parse(raw).text()
                val hint = when {
                    msg.contains("验证码") || body.contains("验证码") -> "验证码错误或已过期，请重新输入"
                    msg.isNotBlank() -> msg
                    else -> "登录失败，请检查账号密码（部分站点需要验证码，建议先在网页版登录）"
                }
                return@withContext Result.failure(Exception(hint))
            }
            Result.success(username)
        }

    suspend fun reply(site: Site, tid: String, fid: String, message: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val refUrl = pageUrl(site, "forum.php?mod=viewthread&tid=$tid", 1)
            val html = http.get(refUrl) ?: return@withContext Result.failure(Exception("无法打开帖子"))
            val formhash = formhashOf(html)
            if (formhash.isBlank()) return@withContext Result.failure(Exception("未登录或登录已过期"))

            val body = http.post(
                u(site, "forum.php?mod=post&action=reply&fid=$fid&tid=$tid&replysubmit=yes&inajax=1&mobile=2"),
                mapOf(
                    "formhash" to formhash,
                    "handlekey" to "fastpost",
                    "message" to message,
                    "usesubject" to "",
                    "posttime" to (System.currentTimeMillis() / 1000).toString()
                ),
                referer = refUrl
            ) ?: return@withContext Result.failure(Exception("发送失败，请检查网络"))

            when {
                body.contains("需要审核") -> Result.success(Unit)
                body.contains("回复发布成功") || body.contains("非常感谢") || body.contains("succeedhandle")
                    || body.contains("post_reply_succeed") -> Result.success(Unit)
                body.contains("抱歉") || body.contains("错误") || body.contains("登录") ->
                    Result.failure(Exception(HtmlBlocks.plain(body, 120)))
                else -> Result.success(Unit)
            }
        }

    suspend fun newThread(site: Site, fid: String, subject: String, message: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val refUrl = pageUrl(site, "forum.php?mod=post&action=newthread&fid=$fid", 1)
            val html = http.get(refUrl) ?: return@withContext Result.failure(Exception("打开发帖页失败"))
            val formhash = formhashOf(html)
            if (formhash.isBlank()) return@withContext Result.failure(Exception("未登录或登录已过期"))

            val body = http.post(
                u(site, "forum.php?mod=post&action=newthread&fid=$fid&topicsubmit=yes&inajax=1&mobile=2"),
                mapOf(
                    "formhash" to formhash,
                    "subject" to subject,
                    "message" to message,
                    "usesig" to "1",
                    "posttime" to (System.currentTimeMillis() / 1000).toString()
                ),
                referer = refUrl
            ) ?: return@withContext Result.failure(Exception("发送失败，请检查网络"))

            if (body.contains("需要审核") || body.contains("succeedhandle") || body.contains("发布成功")) {
                Result.success(Unit)
            } else if (body.contains("抱歉") || body.contains("错误")) {
                Result.failure(Exception(HtmlBlocks.plain(body, 120)))
            } else {
                Result.success(Unit)
            }
        }

    // ---------------------------------------------------------------- 工具

    /** 列表标题常被模板截断成「《书名…》…」，title 属性里才是全名（仅允许的站点调用） */
    private fun fullTitle(a: Element): String {
        val text = a.text().trim()
        val tip = a.attr("title").trim()
        val usable = tip.length > text.length && !tip.contains("新窗口打开") && tip.length <= 120
        return if (usable) tip else text.ifBlank { tip }
    }

    /** 「最新回复」条目指向 redirect&goto=lastpost，直接请求会落到末页，统一改回标准详情地址 */
    private fun threadUrl(a: Element, site: Site, tid: String, href: String): String {
        if (href.contains("mod=redirect", true) || href.contains("goto=lastpost", true)) {
            return u(site, "forum.php?mod=viewthread&tid=$tid")
        }
        return runCatching { a.absUrl("href") }.getOrDefault("").ifBlank { href }
    }

    private fun tidOf(href: String): String? {
        Regex("tid=(\\d+)").find(href)?.let { return it.groupValues[1] }
        Regex("thread-(\\d+)-").find(href)?.let { return it.groupValues[1] }
        return null
    }

}
