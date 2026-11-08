package com.forumassist.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Discuz / PHPWind 系论坛的抓取与解析。
 * 采用「多套选择器逐步降级」的策略，尽量兼容不同模板（含手机版 mobile=2）。
 */
class ForumClient(private val http: Http) {

    private fun u(site: Site, path: String): String =
        site.baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    private fun pageUrl(site: Site, path: String, page: Int): String {
        val sep = if (path.contains("?")) "&" else "?"
        return u(site, path) + sep + "mobile=2" + if (page > 1) "&page=$page" else ""
    }

    // ---------------------------------------------------------------- 版块

    suspend fun boards(site: Site): List<Board> = withContext(Dispatchers.IO) {
        val html = http.get(pageUrl(site, "forum.php", 1)) ?: return@withContext emptyList()
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
        map.values.toList()
    }

    // ---------------------------------------------------------------- 列表

    suspend fun guide(site: Site, view: String, page: Int): List<ThreadSummary> =
        withContext(Dispatchers.IO) {
            val html = http.get(pageUrl(site, "forum.php?mod=guide&view=$view", page))
                ?: return@withContext emptyList()
            if (isServerErrorPage(html)) return@withContext emptyList()
            parseThreads(html, site)
        }

    // ------------------------------------------------- 地址发布页解析

    /**
     * 从「地址发布页」解析站点最新可用域名。
     * 通用策略（不针对单一站点）：
     *  1) 页面里的 <a href> 绝对链接
     *  2) 纯文本中出现的域名
     *  3) base64 混淆内容里解码出的链接
     * 然后逐个探测，命中 Discuz 特征才采用（可过滤掉发布页自身/其它镜像页）。
     */
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
    suspend fun resolveAddress(pageUrl: String): String? = withContext(Dispatchers.IO) {
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

    // ------------------------------------------------- 首页门户四格（最新主题/最新回复/热帖/精华）

    /**
     * 抓站点首页自带门户区块（Discuz 首页四格/DIY）。
     * 这类站点往往限制 guide 接口，但首页区块是公开可见的，且自带作者/时间/查看/回复。
     * view: newthread / newreply / hot / digest
     */
    suspend fun portal(site: Site, view: String): List<ThreadSummary> = withContext(Dispatchers.IO) {
        val html = http.get(site.baseUrl.trimEnd('/') + "/") ?: return@withContext emptyList()
        if (isServerErrorPage(html)) return@withContext emptyList()
        parsePortal(html, site, view)
    }

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

    fun parsePortal(html: String, site: Site, view: String): List<ThreadSummary> {
        val doc = Jsoup.parse(html, site.baseUrl)
        val boxClass = when (view) {
            "newreply" -> "replaybox"
            "hot" -> "hottiebox"
            "digest" -> "goodtiebox"
            else -> "subjectbox"
        }
        val box = doc.selectFirst(".$boxClass")
            ?: doc.selectFirst("h4:contains(最新主题), h4:contains(热帖), h4:contains(精华)")?.parent()
            ?: return emptyList()

        val out = LinkedHashMap<String, ThreadSummary>()
        for (a in box.select("ul.category_newlist li a, li a")) {
            val href = a.attr("href")
            val tid = tidOf(href) ?: continue
            if (out.containsKey(tid)) continue

            // tip 属性: 标题: <strong>xx</strong><br/>作者: 某某 (13 小时前)<br/>查看/回复: 2201/6
            // 注意：标题里本身常含「作者：」（书名+作者），必须按 <br> 分段解析，
            // 否则正则会把标题截断到正文里的「作者：」处，作者也会取错。
            val meta = parseTip(a.attr("tip"))
            val title = meta.title.ifBlank { a.text().trim().ifBlank { a.attr("title").trim() } }
            if (title.length < 2) continue

            val author = meta.author
            val timeRaw = meta.time
            val views = meta.views
            val replies = meta.replies

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
                url = runCatching { a.absUrl("href") }.getOrDefault("").ifBlank { href }
            )
        }
        return out.values.toList()
    }

    suspend fun threads(
        site: Site,
        fid: String,
        page: Int,
        orderBy: String = "lastpost"
    ): List<ThreadSummary> = withContext(Dispatchers.IO) {
        val html = http.get(pageUrl(site, "forum.php?mod=forumdisplay&fid=$fid&orderby=$orderBy", page))
            ?: return@withContext emptyList()
        parseThreads(html, site)
    }

    suspend fun search(site: Site, keyword: String): List<ThreadSummary> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(keyword, "UTF-8")
        val html = http.get(u(site, "search.php?mod=forum&srchtxt=$q&searchsubmit=yes&mobile=2"))
            ?: return@withContext emptyList()
        parseThreads(html, site)
    }

    fun parseThreads(html: String, site: Site): List<ThreadSummary> {
        val doc = Jsoup.parse(html, site.baseUrl)
        val map = LinkedHashMap<String, ThreadSummary>()
        val links = doc.select("a[href*=viewthread], a[href*=thread-]")
        for (a in links) {
            val href = a.attr("href")
            val tid = tidOf(href) ?: continue
            var title = a.text().trim()
            if (title.length < 3) title = a.attr("title").trim()
            if (title.length < 3) continue
            if (title.endsWith("...") && title.length <= 3) continue
            if (map.containsKey(tid)) continue

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
                "\\d{4}-\\d{1,2}-\\d{1,2}([\\s\\u00A0]+\\d{1,2}:\\d{1,2})?|\\d{1,2}-\\d{1,2}[\\s\\u00A0]+\\d{1,2}:\\d{1,2}|\\d+[\\s\\u00A0]*(?:秒|分钟|小时|天)前|昨天"
            ).find(text)?.value ?: ""
            val ts = TimeUtil.parse(lastTimeRaw)
            val fid = Regex("fid=(\\d+)").find(href)?.groupValues?.get(1) ?: ""
            val isTop = box.classNames().any { it.contains("top", true) } || title.startsWith("置顶") ||
                box.selectFirst("em")?.text()?.contains("置顶") == true
            val isDigest = box.selectFirst("img[src*=digest], em")?.text()?.contains("精华") == true
            val isNew = box.classNames().any { it.contains("new", true) }

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
                url = runCatching { a.absUrl("href") }.getOrDefault("").ifBlank { href }
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
                if (raw.isNotEmpty() && page <= 1) {
                    add(if (raw.startsWith("http")) raw else u(site, raw))
                }
                add(pageUrl(site, "forum.php?mod=viewthread&tid=$tid", page))
                add(u(site, "forum.php?mod=viewthread&tid=$tid" + if (page > 1) "&page=$page" else ""))
                add(u(site, "archiver/tid-$tid.html" + if (page > 1) "?page=$page" else ""))
            }.distinct()

            val diag = StringBuilder()
            for (url in candidates) {
                // 带 Referer，某些站点的防采集插件会校验来源
                val html = http.get(url, referer = site.baseUrl.trimEnd('/') + "/forum.php")
                if (html == null) {
                    diag.append("· 请求失败 ").append(url.take(90)).append('\n')
                    continue
                }
                val doc = Jsoup.parse(html, site.baseUrl)
                val bodyCount = doc.select(BODY_SELECTOR).count { it.text().isNotBlank() }
                diag.append("· ").append(url.substringAfter("://").take(70))
                    .append(" | title=").append(doc.title().take(30))
                    .append(" | 正文节点=").append(bodyCount).append('\n')
                if (isLoginWall(html)) {
                    diag.append("  → 判定为登录页，跳过\n")
                    continue
                }
                val parsed = parseDetail(html, site, tid, page) ?: continue
                if (parsed.posts.isNotEmpty()) {
                    lastDetailDebug = "成功：" + parsed.posts.size + " 楼"
                    return@withContext parsed
                }
            }
            lastDetailDebug = diag.toString().ifBlank { "无可用请求" }
            null
        }

    /** 用已渲染好的 HTML（例如 WebView 取回的 DOM）解析详情 */
    fun parseDetailHtml(site: Site, tid: String, page: Int, html: String): ThreadDetail? =
        runCatching { parseDetail(html, site, tid, page) }.getOrNull()

    /**
     * 是否是「请先登录」页。
     * 基准：页面里有没有可解析的正文节点；只有完全没有正文、且明显是登录页时才判定为登录墙。
     * （早期版本用 post_/t_f 特征判断，误把使用 div.message 模板的站点当成登录页）
     */
    private fun isLoginWall(html: String): Boolean {
        if (html.contains("class=\"message\"") || html.contains("postmessage") ||
            html.contains("class=\"t_f\"") || html.contains("id=\"post_")
        ) return false
        val doc = Jsoup.parse(html)
        if (doc.select(BODY_SELECTOR).any { it.text().isNotBlank() }) return false
        val title = doc.title()
        return title.contains("登录") || title.contains("提示") ||
            html.contains("member.php?mod=logging")
    }

    private fun parseDetail(html: String, site: Site, tid: String, page: Int): ThreadDetail? {
        val doc = Jsoup.parse(html, site.baseUrl)
        val title = doc.selectFirst("#thread_subject")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst(".thread-title, .threadtitle, h1.ts, h1")?.text()?.trim()?.ifBlank { null }
            ?: doc.title().substringBefore(" - ").substringBefore("_").trim()
        val posts = parsePosts(doc)
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

    private fun parsePosts(doc: Document): List<Post> {
        // 方案 A：标准/桌面模板 div[id=post_xxx]（含内嵌正文节点）
        val nodes = doc.select("div[id^=post_]").toList().filter {
            it.selectFirst(BODY_SELECTOR) != null
        }
        if (nodes.isNotEmpty()) {
            return nodes.mapIndexed { index, el ->
                val body = el.selectFirst(BODY_SELECTOR) ?: el
                buildPost(el, body, index, extracted = true)
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
            out.add(buildPost(holder, body, out.size, extracted = false))
        }
        return out
    }

    private companion object {
        /** 正文节点选择器，覆盖 Discuz 桌面版 / 手机版 / 常见定制模板 */
        const val BODY_SELECTOR =
            "td.t_f, div.t_f, div.message, div.postmessage, [id^=postmessage_], " +
                ".article-content, .post-content, .thread-content, .post_message"
    }

    private fun buildPost(holder: Element, body: Element, index: Int, extracted: Boolean): Post {
        val pid = holder.id().removePrefix("post_")
        val author = holder.selectFirst("a.xw1, .authi .z a, .mtit a, .authi a, .favatar a, .pi a, .username, .author a, .postinfo a")
            ?.text()?.trim().orEmpty()
        val avatar = holder.selectFirst("img.avatar, .favatar img, .avatar img")?.attr("src").orEmpty()
            .let { if (it.startsWith("//")) "https:$it" else it }
        val uid = Regex("uid=(\\d+)").find(
            holder.selectFirst("a[href*=space-uid]")?.attr("href") ?: ""
        )?.groupValues?.get(1).orEmpty()

        val infoText = holder.selectFirst(".mtime, .pi, .authi, .favatar, .postinfo, .post-date, .time")
            ?.text().orEmpty()
        val timeRaw = Regex(
            "\\d{4}-\\d{1,2}-\\d{1,2}([\\s\\u00A0]+\\d{1,2}:\\d{1,2})?|\\d{1,2}-\\d{1,2}[\\s\\u00A0]+\\d{1,2}:\\d{1,2}|\\d+[\\s\\u00A0]*(?:秒|分钟|小时|天)前|昨天"
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
        return Post(
            pid = pid,
            floor = floor,
            author = author.ifBlank { "匿名" },
            uid = uid,
            avatar = avatar,
            time = timeRaw,
            html = html,
            blocks = HtmlBlocks.parse(html),
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

    private fun tidOf(href: String): String? {
        Regex("tid=(\\d+)").find(href)?.let { return it.groupValues[1] }
        Regex("thread-(\\d+)-").find(href)?.let { return it.groupValues[1] }
        return null
    }
}
