package com.forumassist.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class SiteKind(val label: String) {
    DISCUZ("Discuz"),
    PHPWIND("PHPWind"),
    XENO("XenForo"),
    VBULLETIN("Vbulletin"),
    DEMO("演示")
}

data class Site(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val kind: SiteKind = SiteKind.DISCUZ,
    val enabled: Boolean = true,
    val username: String? = null,
    val cookie: String? = null,
    val color: Int = DEFAULT_COLOR,
    val isDemo: Boolean = false,
    /** 被屏蔽的版块 fid（多为广告版块），不展示、不进信息流 */
    val blockedFids: List<String> = emptyList(),
    /** 地址发布页：域名换了可从这页自动发现最新地址 */
    val addressPage: String = ""
) {
    val host: String
        get() = baseUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')

    val loggedIn: Boolean get() = !cookie.isNullOrBlank()

    companion object {
        const val DEFAULT_COLOR = 0xFF4C6FFF.toInt()
    }
}

fun Site.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("baseUrl", baseUrl)
    put("kind", kind.name)
    put("enabled", enabled)
    put("username", username ?: "")
    put("cookie", cookie ?: "")
    put("color", color)
    put("isDemo", isDemo)
    put("blockedFids", JSONArray(blockedFids))
    put("addressPage", addressPage)
}

fun JSONObject.toSite(): Site = Site(
    id = optString("id", UUID.randomUUID().toString()),
    name = optString("name", "未命名"),
    baseUrl = optString("baseUrl", ""),
    kind = runCatching { SiteKind.valueOf(optString("kind", "DISCUZ")) }.getOrDefault(SiteKind.DISCUZ),
    enabled = optBoolean("enabled", true),
    username = optString("username", "").ifBlank { null },
    cookie = optString("cookie", "").ifBlank { null },
    color = optInt("color", Site.DEFAULT_COLOR),
    isDemo = optBoolean("isDemo", false),
    blockedFids = optJSONArray("blockedFids")?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.optString(it, "").ifBlank { null } }
    }.orEmpty(),
    addressPage = optString("addressPage", "")
)

data class Board(
    val siteId: String,
    val fid: String,
    val name: String,
    val desc: String = "",
    val todayPosts: Int = 0,
    val icon: String = ""
)

/**
 * 版块筛选选项（第一层=排序 orderby，第二层=分类 typeid）
 *
 * query 是这个筛选项对应的真实 URL 参数（如 `filter=heat&orderby=heats`）。
 * 以前是自己拼 filter/orderby，Discuz 各模板的映射并不一致（点「最新发表」换不来内容），
 * 现在直接沿用页面上的链接参数，点什么就请求什么。
 */
data class BoardFilter(
    val key: String,
    val name: String,
    val query: String = ""
)

/**
 * 版块页第一层固定排序（Discuz 通用，与手机版模板一致的顺序）。
 * 不再从页面解析：各模板给出的文案五花八门（还经常出现重复的「查看」），
 * 写死这一套既稳定，又保证每个筛选都真的换了请求参数。
 */
/**
 * 第一层排序的兜底项（页面能解析出排序栏时以页面为准）。
 * query 必须是 Discuz 链接里的原文，别自己翻译 orderby —— 站点只认 filter+orderby 的组合。
 */
val DefaultBoardOrders: List<BoardFilter> = listOf(
    BoardFilter("", "全部", ""),
    BoardFilter("lastpost", "最新", "filter=lastpost&orderby=lastpost"),
    BoardFilter("heats", "热门", "filter=heat&orderby=heats"),
    BoardFilter("hot", "热帖", "filter=hot"),
    BoardFilter("digest", "精华", "filter=digest&digest=1")
)

/** orderby 键 → Discuz 的 filter+orderby 参数（给还在用 orderBy 的旧调用兜底） */
fun boardOrderQuery(orderBy: String): String = when (orderBy) {
    "" -> ""
    "dateline" -> "filter=dateline&orderby=dateline"
    "heats" -> "filter=heat&orderby=heats"
    "digest" -> "filter=digest&digest=1"
    "replies", "views" -> "filter=reply&orderby=replies"
    else -> "filter=lastpost&orderby=lastpost"
}

/** 版块列表一页的结果：帖子 + 页面自带的筛选选项 */
data class ThreadPage(
    val threads: List<ThreadSummary> = emptyList(),
    val orders: List<BoardFilter> = emptyList(),
    val types: List<BoardFilter> = emptyList()
)

/** 固定到底栏「板块」页的快捷入口 */
data class PinnedBoard(
    val siteId: String,
    val fid: String,
    val name: String
) {
    val key: String get() = "$siteId/$fid"
}

fun PinnedBoard.toJson(): JSONObject = JSONObject().apply {
    put("siteId", siteId)
    put("fid", fid)
    put("name", name)
}

fun JSONObject.toPinnedBoard(): PinnedBoard = PinnedBoard(
    siteId = optString("siteId"),
    fid = optString("fid"),
    name = optString("name")
)

data class ThreadSummary(
    val siteId: String,
    val tid: String,
    val fid: String = "",
    val title: String,
    val author: String = "",
    val replies: Int = 0,
    val views: Int = 0,
    val lastTime: String = "",
    val lastReplier: String = "",
    val boardName: String = "",
    val isTop: Boolean = false,
    val isDigest: Boolean = false,
    val isNew: Boolean = false,
    val ts: Long = 0L,
    /** 列表页里抓到的原始帖子链接（可能带 _dsign 等防采集签名，优先用它请求详情） */
    val url: String = "",
    /** 版块分类名（列表页标题前的 [纯爱] 这种） */
    val typeName: String = "",
    /** 帖子标签（标题后的 #ntl #母女花 这类） */
    val tags: List<String> = emptyList()
) {
    val key: String get() = "$siteId/$tid"
}

fun ThreadSummary.toJson(): JSONObject = JSONObject().apply {
    put("siteId", siteId)
    put("tid", tid)
    put("fid", fid)
    put("title", title)
    put("author", author)
    put("replies", replies)
    put("views", views)
    put("lastTime", lastTime)
    put("lastReplier", lastReplier)
    put("boardName", boardName)
    put("isTop", isTop)
    put("isDigest", isDigest)
    put("isNew", isNew)
    put("ts", ts)
    put("url", url)
    put("typeName", typeName)
    put("tags", org.json.JSONArray(tags))
}

fun JSONObject.toThread(): ThreadSummary = ThreadSummary(
    siteId = optString("siteId"),
    tid = optString("tid"),
    fid = optString("fid"),
    title = optString("title"),
    author = optString("author"),
    replies = optInt("replies"),
    views = optInt("views"),
    lastTime = optString("lastTime"),
    lastReplier = optString("lastReplier"),
    boardName = optString("boardName"),
    isTop = optBoolean("isTop"),
    isDigest = optBoolean("isDigest"),
    isNew = optBoolean("isNew"),
    ts = optLong("ts"),
    url = optString("url"),
    typeName = optString("typeName"),
    tags = optJSONArray("tags")?.let { arr ->
        (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
    } ?: emptyList()
)

sealed class Block {
    data class Text(val text: String) : Block()
    /** url 首选，alts 是备选（懒加载图的真实地址可能散在多个属性里） */
    data class Image(val url: String, val alts: List<String> = emptyList()) : Block()
    data class Quote(val text: String) : Block()

    /** 帖子附件（Discuz 的 .pattl / dl.tattl）：文件名 + 下载/购买链接 */
    data class File(
        val name: String,
        val url: String,
        val meta: String = ""
    ) : Block()
}

data class Post(
    val pid: String = "",
    val floor: Int = 0,
    val author: String = "",
    val uid: String = "",
    val avatar: String = "",
    val time: String = "",
    val html: String = "",
    val blocks: List<Block> = emptyList(),
    val isOp: Boolean = false,
    val isOffline: Boolean = false
) {
    val preview: String
        get() = blocks.filterIsInstance<Block.Text>().joinToString(" ") { it.text }.take(400)
}

data class ThreadDetail(
    val summary: ThreadSummary,
    val posts: List<Post>,
    val page: Int = 1,
    val totalPages: Int = 1,
    /**
     * 长正文按段落拆好的「楼层 → 块」，在 IO 线程预先算好，
     * 阅读页直接用它喂 LazyColumn，避免在组合阶段排版几万字卡住首帧。
     */
    val sections: List<List<Block>> = emptyList()
)

fun List<ThreadSummary>.toJsonArray(): JSONArray {
    val arr = JSONArray()
    forEach { arr.put(it.toJson()) }
    return arr
}

fun JSONArray.toThreadList(): List<ThreadSummary> =
    (0 until length()).mapNotNull { runCatching { getJSONObject(it).toThread() }.getOrNull() }

fun List<Post>.toPostJsonArray(): JSONArray {
    val arr = JSONArray()
    forEach { p ->
        arr.put(JSONObject().apply {
            put("pid", p.pid)
            put("floor", p.floor)
            put("author", p.author)
            put("uid", p.uid)
            put("avatar", p.avatar)
            put("time", p.time)
            put("html", p.html)
            put("isOp", p.isOp)
            put("isOffline", p.isOffline)
        })
    }
    return arr
}

fun JSONArray.toPosts(): List<Post> = (0 until length()).mapNotNull { i ->
    runCatching {
        val o = getJSONObject(i)
        val html = o.optString("html")
        Post(
            pid = o.optString("pid"),
            floor = o.optInt("floor"),
            author = o.optString("author"),
            uid = o.optString("uid"),
            avatar = o.optString("avatar"),
            time = o.optString("time"),
            html = html,
            blocks = HtmlBlocks.parse(html),
            isOp = o.optBoolean("isOp"),
            isOffline = o.optBoolean("isOffline")
        )
    }.getOrNull()
}
