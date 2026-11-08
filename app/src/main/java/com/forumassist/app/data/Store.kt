package com.forumassist.app.data

import android.content.Context
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Settings(
    val themeMode: Int = 0,              // 0 跟随系统 / 1 浅色 / 2 深色
    val accent: Int = 0xFF4C6FFF.toInt(),
    val fontScale: Float = 1.0f,
    val demoFallback: Boolean = true,
    val adBlock: Boolean = true,
    /** 是否显示版块页右下角的「发帖」按钮 */
    val showComposeButton: Boolean = true,
    /** 读过的帖子在列表里变灰 */
    val readGray: Boolean = true,
    /** 正文抓取失败时，用隐藏 WebView 再渲染一次（关掉更省流量、更快，但少数帖子只能走网页模式） */
    val webRenderFallback: Boolean = true,
    /** 正文底部是否显示「写下你的回复」输入条 */
    val showReplyBar: Boolean = true,
    val blockedKeywords: List<String> = emptyList(),
    val blockedAuthors: List<String> = emptyList()
)

data class CookieRec(
    val host: String,
    val name: String,
    val value: String,
    val expires: Long,
    val secure: Boolean
)

class Store(context: Context) {

    private val app = context.applicationContext
    private val sp = app.getSharedPreferences("forum_assist", Context.MODE_PRIVATE)

    // ------------------------------------------------------------ 站点

    fun sites(): List<Site> {
        val raw = sp.getString("sites", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { arr.getJSONObject(i).toSite() }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun saveSites(list: List<Site>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString("sites", arr.toString()).apply()
    }

    // ------------------------------------------------------------ 收藏 / 历史 / 已读

    /** 固定到底栏「板块」页的版块 */
    fun pinnedBoards(): List<PinnedBoard> {
        val raw = sp.getString("pinned_boards", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { arr.getJSONObject(i).toPinnedBoard() }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun savePinnedBoards(list: List<PinnedBoard>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString("pinned_boards", arr.toString()).apply()
    }

    fun favorites(): List<ThreadSummary> = readThreads("favorites")
    fun saveFavorites(list: List<ThreadSummary>) = writeThreads("favorites", list)

    fun history(): List<ThreadSummary> = readThreads("history")
    fun saveHistory(list: List<ThreadSummary>) = writeThreads("history", list)

    fun offlineList(): List<ThreadSummary> = readThreads("offline_index")
    fun saveOfflineList(list: List<ThreadSummary>) = writeThreads("offline_index", list)

    private fun readThreads(key: String): List<ThreadSummary> {
        val raw = sp.getString(key, null) ?: return emptyList()
        return runCatching { JSONArray(raw).toThreadList() }.getOrDefault(emptyList())
    }

    private fun writeThreads(key: String, list: List<ThreadSummary>) {
        sp.edit().putString(key, list.toJsonArray().toString()).apply()
    }

    /** 首页每个分栏各存一份，切回来直接复用，不再重新请求 */
    fun feedCache(tab: Int = 0): List<ThreadSummary> =
        readThreads(if (tab <= 0) "feed_cache" else "feed_cache_$tab")

    fun saveFeedCache(list: List<ThreadSummary>, tab: Int = 0) =
        writeThreads(if (tab <= 0) "feed_cache" else "feed_cache_$tab", list.take(200))

    fun readMarks(): MutableMap<String, Int> {
        val raw = sp.getString("read_marks", null) ?: return mutableMapOf()
        return runCatching {
            val obj = JSONObject(raw)
            val map = mutableMapOf<String, Int>()
            obj.keys().forEach { k -> map[k] = obj.optInt(k) }
            map
        }.getOrDefault(mutableMapOf())
    }

    fun saveReadMarks(map: Map<String, Int>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        sp.edit().putString("read_marks", obj.toString()).apply()
    }

    // ------------------------------------------------------------ 设置

    fun settings(): Settings {
        val raw = sp.getString("settings", null) ?: return Settings()
        return runCatching {
            val o = JSONObject(raw)
            Settings(
                themeMode = o.optInt("themeMode", 0),
                accent = o.optInt("accent", 0xFF4C6FFF.toInt()),
                fontScale = o.optDouble("fontScale", 1.0).toFloat(),
                demoFallback = o.optBoolean("demoFallback", true),
                adBlock = o.optBoolean("adBlock", true),
                showComposeButton = o.optBoolean("showComposeButton", true),
                readGray = o.optBoolean("readGray", true),
                webRenderFallback = o.optBoolean("webRenderFallback", true),
                showReplyBar = o.optBoolean("showReplyBar", true),
                blockedKeywords = o.optJSONArray("blockedKeywords")?.toStringList().orEmpty(),
                blockedAuthors = o.optJSONArray("blockedAuthors")?.toStringList().orEmpty()
            )
        }.getOrDefault(Settings())
    }

    fun saveSettings(s: Settings) {
        val o = JSONObject()
        o.put("themeMode", s.themeMode)
        o.put("accent", s.accent)
        o.put("fontScale", s.fontScale.toDouble())
        o.put("demoFallback", s.demoFallback)
        o.put("adBlock", s.adBlock)
        o.put("showComposeButton", s.showComposeButton)
        o.put("readGray", s.readGray)
        o.put("webRenderFallback", s.webRenderFallback)
        o.put("showReplyBar", s.showReplyBar)
        o.put("blockedKeywords", JSONArray(s.blockedKeywords))
        o.put("blockedAuthors", JSONArray(s.blockedAuthors))
        sp.edit().putString("settings", o.toString()).apply()
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it, "").ifBlank { null } }

    // ------------------------------------------------------------ 附件下载目录

    /** SAF 目录 uri（用户自己选的文件夹），没选过就是 null */
    fun downloadTree(): String? = sp.getString("download_tree", null)

    fun saveDownloadTree(uri: String?) {
        sp.edit().apply { if (uri == null) remove("download_tree") else putString("download_tree", uri) }.apply()
    }

    // ------------------------------------------------------------ Cookie

    fun cookies(): List<CookieRec> {
        val raw = sp.getString("cookies", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val o = arr.getJSONObject(i)
                    CookieRec(
                        host = o.getString("host"),
                        name = o.getString("name"),
                        value = o.getString("value"),
                        expires = o.optLong("expires", Long.MAX_VALUE),
                        secure = o.optBoolean("secure", false)
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun saveCookies(list: List<CookieRec>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("host", it.host)
                put("name", it.name)
                put("value", it.value)
                put("expires", it.expires)
                put("secure", it.secure)
            })
        }
        sp.edit().putString("cookies", arr.toString()).apply()
    }

    fun clearCookiesFor(site: Site) {
        val host = runCatching { site.baseUrl.toHttpUrlOrNull()?.host }.getOrNull() ?: return
        saveCookies(cookies().filterNot { it.host == host })
    }

    // ------------------------------------------------------------ 离线文章

    private fun offlineDir(): File = File(app.filesDir, "offline").apply { mkdirs() }

    private fun offlineFile(siteId: String, tid: String) =
        File(offlineDir(), siteId.replace("/", "_") + "_" + tid + ".json")

    fun saveOffline(detail: ThreadDetail) {
        runCatching {
            val o = JSONObject()
            o.put("summary", detail.summary.toJson())
            o.put("posts", detail.posts.toPostJsonArray())
            o.put("page", detail.page)
            o.put("totalPages", detail.totalPages)
            offlineFile(detail.summary.siteId, detail.summary.tid).writeText(o.toString())
        }
    }

    fun loadOffline(siteId: String, tid: String): ThreadDetail? = runCatching {
        val f = offlineFile(siteId, tid)
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        ThreadDetail(
            summary = o.getJSONObject("summary").toThread(),
            posts = o.getJSONArray("posts").toPosts(),
            page = o.optInt("page", 1),
            totalPages = o.optInt("totalPages", 1)
        )
    }.getOrNull()

    // ------------------------------------------------------------ 正文读速缓存（非「离线保存」）

    private fun detailCacheDir(): File = File(app.filesDir, "dcache").apply { mkdirs() }

    private fun detailCacheFile(siteId: String, tid: String) =
        File(detailCacheDir(), siteId.replace("/", "_") + "_" + tid + ".json")

    /** 存一份上次解析好的正文：下次进来可以先秒开，再后台刷新 */
    fun saveDetailCache(detail: ThreadDetail) {
        runCatching {
            val o = JSONObject()
            o.put("at", System.currentTimeMillis())
            o.put("summary", detail.summary.toJson())
            o.put("posts", detail.posts.toPostJsonArray())
            o.put("page", detail.page)
            o.put("totalPages", detail.totalPages)
            detailCacheFile(detail.summary.siteId, detail.summary.tid).writeText(o.toString())
        }
    }

    fun loadDetailCache(siteId: String, tid: String, maxAgeMs: Long = 7L * 24 * 60 * 60 * 1000): ThreadDetail? =
        runCatching {
            val f = detailCacheFile(siteId, tid)
            if (!f.exists()) return null
            val o = JSONObject(f.readText())
            if (System.currentTimeMillis() - o.optLong("at", 0L) > maxAgeMs) return null
            ThreadDetail(
                summary = o.getJSONObject("summary").toThread(),
                posts = o.getJSONArray("posts").toPosts(),
                page = o.optInt("page", 1),
                totalPages = o.optInt("totalPages", 1)
            )
        }.getOrNull()

    fun deleteOffline(siteId: String, tid: String) {
        offlineFile(siteId, tid).delete()
        saveOfflineList(offlineList().filterNot { it.siteId == siteId && it.tid == tid })
    }

    fun cacheSize(): Long {
        var size = 0L
        app.filesDir.walkTopDown().forEach {
            // 日志/离线索引这类小文件不算「缓存」，否则清理前后数字对不上
            if (it.isFile && !it.name.endsWith(".log")) size += it.length()
        }
        // Coil 的图片缓存放在 cacheDir 下，之前没算进来：看着只有零点几 MB，其实图片占了大部分
        File(app.cacheDir, "image_cache").walkTopDown().forEach { if (it.isFile) size += it.length() }
        return size
    }

    /**
     * 清缓存。包含：
     *  - 离线保存的正文
     *  - 正文「读速缓存」（dcache，进帖子秒开用的那一份）
     *  - 首页信息流缓存
     *  - 图片缓存（磁盘 + 内存）
     * 收藏、已读记录、登录态不动。
     */
    fun clearCache(): Boolean {
        // 先让 Coil 放手（它持有磁盘缓存的 journal），再删目录，否则可能残留
        runCatching {
            val loader = coil.Coil.imageLoader(app)
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        }
        var ok = true
        listOf(
            offlineDir(),
            detailCacheDir(),
            File(app.cacheDir, "image_cache")
        ).forEach { dir ->
            if (dir.exists()) ok = dir.deleteRecursively() && ok
        }
        sp.edit()
            .remove("offline_index")
            .remove("history")
            .remove("feed_cache")
            .remove("feed_cache_1")
            .remove("feed_cache_2")
            .apply()
        return ok
    }

    // ------------------------------------------------------------ 杂项

    var firstRun: Boolean
        get() = sp.getBoolean("first_run", true)
        set(value) = sp.edit().putBoolean("first_run", value).apply()

    var lastFeedRefresh: Long
        get() = sp.getLong("last_feed", 0L)
        set(value) = sp.edit().putLong("last_feed", value).apply()
}
