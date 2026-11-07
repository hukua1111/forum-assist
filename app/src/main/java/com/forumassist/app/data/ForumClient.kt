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
            parseThreads(html, site)
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
                "\\d{4}-\\d{1,2}-\\d{1,2}(\\s+\\d{1,2}:\\d{1,2})?|\\d{1,2}-\\d{1,2}\\s+\\d{1,2}:\\d{1,2}|\\d+\\s*(?:秒|分钟|小时|天)前|昨天"
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
                ts = ts
            )
        }
        return map.values.toList()
    }

    // ---------------------------------------------------------------- 详情

    suspend fun detail(site: Site, tid: String, page: Int = 1): ThreadDetail? =
        withContext(Dispatchers.IO) {
            val url = pageUrl(site, "forum.php?mod=viewthread&tid=$tid", page)
            val html = http.get(url) ?: return@withContext null
            val doc = Jsoup.parse(html, site.baseUrl)
            val title = doc.selectFirst("#thread_subject")?.text()
                ?: doc.title().substringBefore(" - ").substringBefore("_").trim()
            val posts = parsePosts(doc)
            if (posts.isEmpty()) return@withContext null

            val totalPages = Regex("page=(\\d+)").findAll(html).mapNotNull { it.groupValues[1].toIntOrNull() }
                .maxOrNull() ?: 1
            val fid = Regex("fid=(\\d+)").find(html)?.groupValues?.get(1) ?: ""
            val summary = ThreadSummary(
                siteId = site.id,
                tid = tid,
                fid = fid,
                title = title,
                author = posts.first().author,
                replies = Regex("(\\d+)\\s*(?:个)?回复").find(doc.text())?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                views = 0,
                lastTime = posts.first().time,
                ts = TimeUtil.parse(posts.first().time)
            )
            ThreadDetail(summary = summary, posts = posts, page = page, totalPages = totalPages)
        }

    private fun parsePosts(doc: Document): List<Post> {
        val out = mutableListOf<Post>()

        // 方案 A：Discuz 标准结构 div[id=post_xxx]
        var nodes = doc.select("div[id^=post_]").toList().filter {
            it.selectFirst(".t_f, .message, .postmessage, .pcb") != null
        }

        // 方案 B：直接抓正文元素
        if (nodes.isEmpty()) {
            val bodies = doc.select("td.t_f, div.t_f, div.message, div.postmessage")
            val seen = mutableSetOf<String>()
            bodies.forEach { body ->
                val holder = body.closest("div[id^=post_]")
                    ?: body.closest(".plc")
                    ?: body.closest(".postlist")
                    ?: body.parent()?.parent()
                if (holder == null) return@forEach
                if (!seen.add(holder.cssSelector() + holder.text().take(20))) return@forEach
                out.add(buildPost(holder, body, out.size, extracted = false))
            }
            return out
        }

        for ((index, el) in nodes.withIndex()) {
            val body = el.selectFirst("td.t_f, div.t_f, div.message, div.postmessage, .pcb") ?: continue
            out.add(buildPost(el, body, index, extracted = true))
        }
        return out
    }

    private fun buildPost(holder: Element, body: Element, index: Int, extracted: Boolean): Post {
        val pid = holder.id().removePrefix("post_")
        val author = holder.selectFirst("a.xw1, .authi a, .favatar a, .pi a")?.text()?.trim().orEmpty()
        val avatar = holder.selectFirst("img.avatar, .favatar img, .avatar img")?.attr("src").orEmpty()
            .let { if (it.startsWith("//")) "https:$it" else it }
        val uid = Regex("uid=(\\d+)").find(
            holder.selectFirst("a[href*=space-uid]")?.attr("href") ?: ""
        )?.groupValues?.get(1).orEmpty()

        val infoText = holder.selectFirst(".pi, .authi, .favatar")?.text().orEmpty()
        val timeRaw = Regex(
            "\\d{4}-\\d{1,2}-\\d{1,2}(\\s+\\d{1,2}:\\d{1,2})?|\\d{1,2}-\\d{1,2}\\s+\\d{1,2}:\\d{1,2}|\\d+\\s*(?:分钟|小时|天)前|昨天"
        ).find(infoText)?.value.orEmpty()

        val floorText = holder.selectFirst("strong a em, .pi strong, #authorposton$pid")?.text().orEmpty()
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

    suspend fun login(site: Site, username: String, password: String): Result<String> =
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

            val body = http.post(
                postUrl,
                mapOf(
                    "formhash" to formhash,
                    "referer" to u(site, "forum.php"),
                    "loginfield" to "username",
                    "username" to username,
                    "password" to password,
                    "questionid" to "0",
                    "answer" to "",
                    "cookietime" to "2592000"
                ),
                referer = loginUrl
            ) ?: return@withContext Result.failure(Exception("登录请求失败，请检查网络"))

            val ok = body.contains("欢迎您回来") || body.contains("现在将转入登录前页面") ||
                body.contains("欢迎你回来") || http.hasLoginCookie(site)
            if (!ok) {
                val msg = Regex("errorhandle_[a-z]*\">([^<]+)<").find(body)?.groupValues?.get(1)
                    ?: Regex("<p>([^<]{4,60})</p>").find(body)?.groupValues?.get(1)
                    ?: "登录失败，请检查账号密码（部分站点需要验证码，建议先在网页版登录）"
                return@withContext Result.failure(Exception(Jsoup.parse(msg).text()))
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
