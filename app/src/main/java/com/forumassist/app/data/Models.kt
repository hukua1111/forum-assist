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
    val blockedFids: List<String> = emptyList()
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
    }.orEmpty()
)

data class Board(
    val siteId: String,
    val fid: String,
    val name: String,
    val desc: String = "",
    val todayPosts: Int = 0,
    val icon: String = ""
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
    val url: String = ""
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
    url = optString("url")
)

sealed class Block {
    data class Text(val text: String) : Block()
    data class Image(val url: String) : Block()
    data class Quote(val text: String) : Block()
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
    val totalPages: Int = 1
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
