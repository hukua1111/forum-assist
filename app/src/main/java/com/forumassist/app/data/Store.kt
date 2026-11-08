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

    fun feedCache(): List<ThreadSummary> = readThreads("feed_cache")
    fun saveFeedCache(list: List<ThreadSummary>) = writeThreads("feed_cache", list.take(200))

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
        o.put("blockedKeywords", JSONArray(s.blockedKeywords))
        o.put("blockedAuthors", JSONArray(s.blockedAuthors))
        sp.edit().putString("settings", o.toString()).apply()
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it, "").ifBlank { null } }

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

    fun deleteOffline(siteId: String, tid: String) {
        offlineFile(siteId, tid).delete()
        saveOfflineList(offlineList().filterNot { it.siteId == siteId && it.tid == tid })
    }

    fun cacheSize(): Long {
        var size = 0L
        app.filesDir.walkTopDown().forEach { if (it.isFile) size += it.length() }
        return size
    }

    fun clearCache() {
        offlineDir().deleteRecursively()
        sp.edit().remove("offline_index").remove("history").apply()
    }

    // ------------------------------------------------------------ 杂项

    var firstRun: Boolean
        get() = sp.getBoolean("first_run", true)
        set(value) = sp.edit().putBoolean("first_run", value).apply()

    var lastFeedRefresh: Long
        get() = sp.getLong("last_feed", 0L)
        set(value) = sp.edit().putLong("last_feed", value).apply()
}
